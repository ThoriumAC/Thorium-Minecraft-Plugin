package ac.thorium.mc.plugin.world;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.proto.SectionPos;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Keeps the mirror fed. Runs a tick timer that decides which sections should be
 * mirrored, then spends a bounded per-tick budget taking chunk snapshots on the
 * thread that owns each chunk and handing the decode to a worker.
 *
 * <p>The only main-thread (or region-thread) work is {@code getChunkSnapshot()},
 * which is an in-memory palette copy. Turning 4096 blocks into state strings —
 * the expensive part — happens on the async scheduler, stamped with a snapshot
 * token so changes landing mid-decode are not lost.
 */
public final class WorldSampler {
    /** How often the wanted set is recomputed from player positions. */
    static final int RETARGET_EVERY_TICKS = 10;

    /**
     * Sections below and above the player's own that get mirrored. Asymmetric on
     * purpose: collision and fall checks need the ground under a player, while
     * sections of open sky above them are dead weight on both the decode and the
     * wire. Four sections per column instead of five.
     */
    static final int SECTIONS_BELOW = 2;
    static final int SECTIONS_ABOVE = 1;
    /** Sections mirrored per column. */
    static final int SECTIONS_PER_COLUMN = SECTIONS_BELOW + SECTIONS_ABOVE + 1;

    /**
     * Decode threads. Deliberately few and deliberately below normal priority: the
     * decode is already off the server thread, and at a high column budget its CPU
     * and allocation rate — not its thread placement — is what starves the main
     * thread and shows up as tick spikes. Capping it is the fix; adding threads is not.
     */
    private static final int DECODE_THREADS = 2;
    /** Snapshots allowed to queue before the sampler stops taking more. */
    private static final int DECODE_QUEUE = 64;

    private final WorldMirror mirror;
    private final Scheduler sched;
    private final ErrorGate gate;
    private final Server server;
    /**
     * Whether the engine is there to receive any of this.
     *
     * <p>The mirror's {@code enabled} flag only ever changes on an IngestPolicy,
     * so a disconnect leaves it on: without this check the sampler went on
     * taking chunk snapshots on the server thread and feeding the decoders for
     * as long as the engine was away - a whole hour of it after the gateway
     * closes with CLOSE_OUTDATED - with nothing at the far end.
     */
    private final BooleanSupplier connected;

    private Object tickTask;
    private long ticks;
    private ThreadPoolExecutor decoders;

    /**
     * Biome per column, read once and kept: worldgen fixes it, and asking for it
     * again means another biome copy inside getChunkSnapshot on the server thread.
     * Bounded by the same reach as the mirror itself; cleared with it.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> biomeByColumn =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private volatile boolean calibrated;

    public WorldSampler(WorldMirror mirror, Scheduler sched, ErrorGate gate, Server server, BooleanSupplier connected) {
        this.mirror = mirror;
        this.sched = sched;
        this.gate = gate;
        this.server = server;
        this.connected = connected;
    }

    public void start() {
        stop();
        decoders = new ThreadPoolExecutor(DECODE_THREADS, DECODE_THREADS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(DECODE_QUEUE), r -> {
            Thread t = new Thread(r, "Thorium-WorldDecode");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());   // a dropped snapshot is re-queued by the next retarget
        tickTask = sched.runGlobalTimer(() -> gate.run("world:sample", this::tick), 1, 1);
    }

    public void stop() {
        if (tickTask != null) sched.cancel(tickTask);
        tickTask = null;
        if (decoders != null) decoders.shutdownNow();
        decoders = null;
        biomeByColumn.clear();
    }

    private void tick() {
        if (!mirror.enabled() || !connected.getAsBoolean()) return;
        ticks++;
        if (ticks % RETARGET_EVERY_TICKS == 0) {
            mirror.retarget(wantedSections(), occupiedColumns(), ticks);
        }
        // Backpressure: taking snapshots the decoders cannot keep up with only pins
        // memory and deepens the spike. The columns stay queued for a later tick.
        ThreadPoolExecutor ex = decoders;
        if (ex == null || ex.getQueue().size() >= DECODE_QUEUE / 2) return;
        for (WorldMirror.ColumnPos c : mirror.nextColumns(mirror.columnsPerTick())) snapshot(c);
    }

    /**
     * Every section within the configured radius of an online player, in priority
     * order: ring by ring outward, interleaved across players.
     *
     * <p>Order matters because the mirror truncates the wanted set at its section
     * cap. Emitting rings outward and interleaving players means the cap keeps the
     * blocks nearest to feet — the ones collision actually needs — and gives every
     * player their inner ring before anyone gets an outer one. It also has to be
     * *stable*: a plain HashSet hands back a different arbitrary subset on every
     * retarget, so past the cap the mirror spends its whole snapshot budget
     * dropping and re-reading sections instead of converging.
     */
    /**
     * The column each online player is standing in.
     *
     * <p>These are re-read far more often than the rest of the mirror. Nothing
     * reports a /fill, a /setblock, a WorldEdit paste or a plugin's
     * Block.setType, so ground that changed under a player stays wrong in the
     * mirror until something looks again - and the engine, believing it, has a
     * player standing on nothing.
     */
    private List<WorldMirror.ColumnPos> occupiedColumns() {
        List<WorldMirror.ColumnPos> out = new ArrayList<WorldMirror.ColumnPos>();
        for (Player p : server.getOnlinePlayers()) {
            Location loc = p.getLocation();
            World w = loc.getWorld();
            if (w == null) continue;
            out.add(new WorldMirror.ColumnPos(w.getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
        }
        return out;
    }

    private Set<SectionPos> wantedSections() {
        Set<SectionPos> out = new LinkedHashSet<SectionPos>();
        int r = mirror.radiusChunks();
        List<Player> players = new ArrayList<Player>(server.getOnlinePlayers());
        for (int ring = 0; ring <= r; ring++) {
            for (Player p : players) {
                Location loc = p.getLocation();
                World w = loc.getWorld();
                if (w == null) continue;
                String dim = w.getName();
                int minSy = SnapshotReader.minSectionY(w);
                int maxSy = SnapshotReader.maxSectionY(w);
                int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4, cy = loc.getBlockY() >> 4;
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        // Only the shell of this ring; inner rings were emitted already.
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        for (int dy = -SECTIONS_BELOW; dy <= SECTIONS_ABOVE; dy++) {
                            int sy = cy + dy;
                            if (sy < minSy || sy > maxSy) continue;
                            out.add(SectionPos.newBuilder().setDimension(dim).setX(cx + dx).setY(sy).setZ(cz + dz).build());
                        }
                    }
                }
            }
        }
        return out;
    }

    private void snapshot(final WorldMirror.ColumnPos c) {
        final World w = server.getWorld(c.dimension);
        // Never force-load a chunk for telemetry; if it is not resident, skip it and
        // let the next retarget pick it up.
        if (w == null || !w.isChunkLoaded(c.x, c.z)) return;
        final List<SectionPos> want = mirror.wantedIn(c);
        if (want.isEmpty()) return;
        final int minSy = SnapshotReader.minSectionY(w);
        final int maxSy = SnapshotReader.maxSectionY(w);
        // The engine needs where this world ends, not just what is in it.
        mirror.bounds(c.dimension, SnapshotReader.minHeight(w), w.getMaxHeight());
        final String columnKey = c.dimension + ":" + c.x + "," + c.z;
        // Copying biomes costs main-thread time in getChunkSnapshot, and a column's
        // biome does not change. Ask for it the first time the column is read and
        // reuse the answer on every later sweep.
        final boolean needBiome = !biomeByColumn.containsKey(columnKey);
        sched.runForChunk(w, c.x, c.z, () -> gate.run("world:snapshot", () -> {
            if (!w.isChunkLoaded(c.x, c.z)) return;
            // Stamp before the read so anything that changes while the decode is in
            // flight is still treated as newer than the snapshot.
            final long token = mirror.snapshotToken();
            final long t0 = System.nanoTime();
            // (maxBlockY, biome, biomeTempRain). A snapshot taken without biomes
            // throws on getBiome rather than returning a default, so the flag and
            // the read below have to agree.
            final ChunkSnapshot snap = w.getChunkAt(c.x, c.z).getChunkSnapshot(false, needBiome, false);
            mirror.recordSnapshot(System.nanoTime() - t0);
            ThreadPoolExecutor ex = decoders;
            if (ex != null) {
                ex.execute(() -> gate.run("world:decode",
                        () -> decode(snap, want, token, minSy, maxSy, columnKey, needBiome)));
            }
        }));
    }

    private void decode(ChunkSnapshot snap, List<SectionPos> want, long token, int minSy, int maxSy,
                        String columnKey, boolean readBiome) {
        if (!calibrated) {
            calibrated = true;
            SnapshotReader.calibrate(snap, minSy, maxSy);
        }
        // One read per column rather than per section: a 16-block cube is already
        // coarser than the client's per-block blend, and the viewer only needs it
        // to tell a swamp from a plain.
        String biome = biomeByColumn.get(columnKey);
        if (readBiome) {
            biome = SnapshotReader.biomeAt(snap, 8, 64, 8);
            biomeByColumn.put(columnKey, biome == null ? "" : biome);
        }
        if (biome == null) biome = "";
        for (SectionPos p : want) {
            SectionData d = SnapshotReader.readSection(snap, p.getY(), minSy, maxSy);
            if (d == null) continue;
            mirror.acceptSection(p, d, token, biome);
        }
    }

    /** Section coordinates of a world block position. */
    public static SectionPos sectionOf(String dimension, int x, int y, int z) {
        return SectionPos.newBuilder().setDimension(dimension).setX(x >> 4).setY(y >> 4).setZ(z >> 4).build();
    }

    /** Section-local offset of a world block position. */
    public static int offsetOf(int x, int y, int z) {
        return SectionData.offset(x & 15, y & 15, z & 15);
    }

    /** Distinct sections spanning a range of world y at one column, for coarse invalidation. */
    public static List<SectionPos> sectionsSpanning(String dimension, int x, int y1, int y2, int z) {
        List<SectionPos> out = new ArrayList<SectionPos>();
        int lo = Math.min(y1, y2) >> 4, hi = Math.max(y1, y2) >> 4;
        for (int sy = lo; sy <= hi; sy++) {
            out.add(SectionPos.newBuilder().setDimension(dimension).setX(x >> 4).setY(sy).setZ(z >> 4).build());
        }
        return out;
    }
}
