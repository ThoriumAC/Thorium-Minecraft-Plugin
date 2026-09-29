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

public final class WorldSampler {
    static final int RETARGET_EVERY_TICKS = 10;

    static final int SECTIONS_BELOW = 2;
    static final int SECTIONS_ABOVE = 1;
    static final int SECTIONS_PER_COLUMN = SECTIONS_BELOW + SECTIONS_ABOVE + 1;

    private static final int DECODE_THREADS = 2;
    private static final int DECODE_QUEUE = 64;

    private final WorldMirror mirror;
    private final Scheduler sched;
    private final ErrorGate gate;
    private final Server server;
    private final BooleanSupplier connected;

    private Object tickTask;
    private long ticks;
    private ThreadPoolExecutor decoders;

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
        }, new ThreadPoolExecutor.DiscardPolicy());
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
        ThreadPoolExecutor ex = decoders;
        if (ex == null || ex.getQueue().size() >= DECODE_QUEUE / 2) return;
        for (WorldMirror.ColumnPos c : mirror.nextColumns(mirror.columnsPerTick())) snapshot(c);
    }

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
        if (w == null || !w.isChunkLoaded(c.x, c.z)) return;
        final List<SectionPos> want = mirror.wantedIn(c);
        if (want.isEmpty()) return;
        final int minSy = SnapshotReader.minSectionY(w);
        final int maxSy = SnapshotReader.maxSectionY(w);
        mirror.bounds(c.dimension, SnapshotReader.minHeight(w), w.getMaxHeight());
        final String columnKey = c.dimension + ":" + c.x + "," + c.z;
        final boolean needBiome = !biomeByColumn.containsKey(columnKey);
        sched.runForChunk(w, c.x, c.z, () -> gate.run("world:snapshot", () -> {
            if (!w.isChunkLoaded(c.x, c.z)) return;
            final long token = mirror.snapshotToken();
            final long t0 = System.nanoTime();
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

    public static SectionPos sectionOf(String dimension, int x, int y, int z) {
        return SectionPos.newBuilder().setDimension(dimension).setX(x >> 4).setY(y >> 4).setZ(z >> 4).build();
    }

    public static int offsetOf(int x, int y, int z) {
        return SectionData.offset(x & 15, y & 15, z & 15);
    }

    public static List<SectionPos> sectionsSpanning(String dimension, int x, int y1, int y2, int z) {
        List<SectionPos> out = new ArrayList<SectionPos>();
        int lo = Math.min(y1, y2) >> 4, hi = Math.max(y1, y2) >> 4;
        for (int sy = lo; sy <= hi; sy++) {
            out.add(SectionPos.newBuilder().setDimension(dimension).setX(x >> 4).setY(sy).setZ(z >> 4).build());
        }
        return out;
    }
}
