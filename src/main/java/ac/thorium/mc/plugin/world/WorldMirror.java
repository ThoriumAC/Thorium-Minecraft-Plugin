package ac.thorium.mc.plugin.world;

import ac.thorium.mc.proto.BlockChange;
import ac.thorium.mc.proto.Section;
import ac.thorium.mc.proto.SectionChanges;
import ac.thorium.mc.proto.SectionPos;
import ac.thorium.mc.proto.UpStream;
import ac.thorium.mc.proto.WorldBounds;
import ac.thorium.mc.proto.WorldChunk;
import ac.thorium.mc.proto.WorldDelta;
import ac.thorium.mc.proto.WorldPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks which sections the engine holds and what still needs sending. Pure
 * state: no Bukkit types, no threads, no clock of its own.
 *
 * <p>The lifecycle is a full sync followed by deltas. {@link #reset()} arms a
 * full sync; {@link #retarget} says which sections should be mirrored right now
 * and yields the columns still needing a snapshot; the sampler drains that queue
 * against a per-tick budget and feeds results back through {@link #acceptSection};
 * block events land in {@link #recordChange}; and the flush worker turns whatever
 * has accumulated into frames via {@link #drain}.
 *
 * <p><b>Ordering contract:</b> a snapshot must be stamped with {@link #snapshotToken()}
 * on the thread that owns the chunk, immediately before the world is read. Every
 * queued block change carries a sequence number, so accepting a section discards
 * exactly the changes the snapshot already contains and keeps the ones that came
 * after it. That lets the expensive 4096-block decode run on a worker thread
 * without losing a change that landed while it was in flight.
 *
 * <p>All methods are safe to call concurrently.
 */
public final class WorldMirror {
    /** A chunk column; the unit Bukkit hands out snapshots in. */
    public static final class ColumnPos {
        public final String dimension;
        public final int x, z;

        public ColumnPos(String dimension, int x, int z) {
            this.dimension = dimension == null ? "" : dimension;
            this.x = x;
            this.z = z;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ColumnPos)) return false;
            ColumnPos c = (ColumnPos) o;
            return x == c.x && z == c.z && dimension.equals(c.dimension);
        }

        @Override public int hashCode() { return (dimension.hashCode() * 31 + x) * 31 + z; }
        @Override public String toString() { return dimension + "@" + x + "," + z; }
    }

    public static final int DEFAULT_RADIUS_CHUNKS = 4;
    public static final int DEFAULT_COLUMNS_PER_TICK = 2;
    private static final int MAX_SECTIONS = 8192;          // hard ceiling on the mirrored world
    private static final int MAX_DELTAS = 16384;           // hard ceiling on queued block changes
    private static final int DEFAULT_MAX_FRAME_BYTES = 192 * 1024;
    /**
     * How stale a held section may get before the world is re-read for it. This is
     * the backstop for changes no event told us about — block physics above all,
     * which is far too hot to listen to directly.
     */
    public static final long DEFAULT_VERIFY_INTERVAL_TICKS = 2400;   // 2 minutes
    /**
     * Columns re-verified per retarget. Bounded and spread over time on purpose:
     * re-queueing every held column at once produces a burst of snapshots and
     * decodes that lands as a tick spike, and at high player counts it would swamp
     * the snapshot budget entirely. A slow, even rotation costs the same work and
     * none of the spike.
     */
    public static final int DEFAULT_VERIFY_COLUMNS = 8;
    /**
     * How stale the column a player is standing in may get. Far shorter than the
     * general sweep, because this is the one place staleness turns into a
     * verdict.
     *
     * <p>Nothing tells the plugin about /fill, /setblock, WorldEdit, or any
     * plugin calling Block.setType: none of them fire a Bukkit block event, so
     * the only way the mirror ever learns is by looking again. Two minutes of
     * that under someone's feet reads to the engine as a player standing on
     * nothing, and the movement checks say so - the compat matrix caught a
     * legit bot flagged for hovering while it stood still on a floor that had
     * been filled in after the snapshot.
     */
    public static final long DEFAULT_HOT_INTERVAL_TICKS = 60;   // 3 seconds
    /** Hot columns re-queued per retarget. One per player is the intent. */
    public static final int DEFAULT_HOT_COLUMNS = 4;

    private volatile boolean enabled;
    private volatile int radiusChunks = DEFAULT_RADIUS_CHUNKS;
    private volatile int columnsPerTick = DEFAULT_COLUMNS_PER_TICK;

    /** What the engine holds: content hash plus when the world was last read for it. */
    private static final class Held {
        long hash;
        long verifiedAtTick;
        Held(long hash, long tick) { this.hash = hash; this.verifiedAtTick = tick; }
    }

    private final Map<SectionPos, Held> held = new LinkedHashMap<SectionPos, Held>();
    /** Sections encoded and waiting for the flush worker. */
    private final Map<SectionPos, Section> outbox = new LinkedHashMap<SectionPos, Section>();
    /** Block changes per section, deduplicated by offset (last write wins). */
    private final Map<SectionPos, LinkedHashMap<Integer, Change>> deltas =
            new LinkedHashMap<SectionPos, LinkedHashMap<Integer, Change>>();
    /** Columns still needing a snapshot, in insertion order. */
    private final LinkedHashSet<ColumnPos> pendingColumns = new LinkedHashSet<ColumnPos>();
    /** The sections the sampler should keep, refreshed by retarget. */
    private final Set<SectionPos> wanted = new HashSet<SectionPos>();
    private final List<SectionPos> dropped = new ArrayList<SectionPos>();

    private boolean fullSyncPending;
    private boolean syncing;
    /**
     * Whether retarget has run since the sync was armed. The flush worker drains
     * every 75 ms but the sampler only retargets every 10 ticks, so without this
     * the first drain after reset sees an empty queue and declares a sync of zero
     * sections complete.
     */
    private boolean retargeted;
    private long tickNow;
    private long verifyIntervalTicks = DEFAULT_VERIFY_INTERVAL_TICKS;
    private int verifyColumnsPerRetarget = DEFAULT_VERIFY_COLUMNS;
    private long hotIntervalTicks = DEFAULT_HOT_INTERVAL_TICKS;
    private int hotColumnsPerRetarget = DEFAULT_HOT_COLUMNS;
    /** When each column was last read from the world, for the hot sweep. */
    private final Map<ColumnPos, Long> columnVerified = new LinkedHashMap<ColumnPos, Long>();

    /**
     * How far each world extends vertically, and whether the engine has been told.
     *
     * <p>The engine cannot work this out for itself. A section below the floor and
     * a section that has not been streamed yet are the same absence from its
     * mirror, so without this it has to call a player under the world unplaceable
     * and leave them alone - which is exactly where a ground spoof puts them.
     */
    private final Map<String, WorldBounds> bounds = new LinkedHashMap<String, WorldBounds>();
    private boolean boundsPending;
    private long droppedChanges;
    private long changeSeq;
    /** Main-thread (or region-thread) time spent inside getChunkSnapshot. */
    private long snapshotNanos;
    private long snapshotCount;

    /** A queued block change, stamped so a snapshot can tell which changes it already contains. */
    private static final class Change {
        final long seq;
        final String block;
        Change(long seq, String block) { this.seq = seq; this.block = block; }
    }

    public void setPolicy(WorldPolicy p) {
        boolean was = enabled;
        enabled = p != null && p.getEnabled();
        if (p != null) {
            radiusChunks = p.getRadiusChunks() > 0 ? Math.min(16, p.getRadiusChunks()) : DEFAULT_RADIUS_CHUNKS;
            columnsPerTick = p.getColumnsPerTick() > 0 ? Math.min(64, p.getColumnsPerTick()) : DEFAULT_COLUMNS_PER_TICK;
        }
        if (enabled && !was) reset();
        if (!enabled && was) clear();
    }

    public boolean enabled() { return enabled; }
    public int radiusChunks() { return radiusChunks; }
    public int columnsPerTick() { return columnsPerTick; }

    /** Arms a fresh full sync: the engine is told to drop its model and everything is resent. */
    public synchronized void reset() {
        clearLocked();
        fullSyncPending = true;
        syncing = true;
    }

    /**
     * Records a world's vertical extent, to be sent with the next chunk frame.
     *
     * <p>Only a change is sent. These are read off every snapshot, which is
     * thousands of times a minute for a handful of worlds that never move.
     */
    public synchronized void bounds(String dimension, int minY, int maxY) {
        if (dimension == null || dimension.isEmpty() || maxY <= minY) return;
        WorldBounds b = WorldBounds.newBuilder().setDimension(dimension).setMinY(minY).setMaxY(maxY).build();
        WorldBounds had = bounds.get(dimension);
        if (b.equals(had)) return;
        bounds.put(dimension, b);
        boundsPending = true;
    }

    /** Forgets everything without arming a sync (streaming turned off, or the pipeline stopped). */
    public synchronized void clear() { clearLocked(); }

    private void clearLocked() {
        held.clear();
        outbox.clear();
        deltas.clear();
        pendingColumns.clear();
        wanted.clear();
        dropped.clear();
        fullSyncPending = false;
        syncing = false;
        retargeted = false;
        // Bounds are not forgotten - they describe the server's worlds, not this
        // sync - but the engine that is about to drop its model needs them again.
        boundsPending = !bounds.isEmpty();
    }

    /**
     * Declares the sections that should be mirrored right now. Sections that are
     * wanted but not held queue their column for a snapshot; sections that are
     * held but no longer wanted are forgotten and reported to the engine.
     *
     * <p>Sections already held are re-read only once they go stale. The plugin's
     * block-event list is deliberately incomplete — {@code BlockPhysicsEvent} alone
     * would cost more TPS than the mirror saves — so a bounded rotation of re-reads
     * is what corrects the drift, at {@link #DEFAULT_VERIFY_COLUMNS} columns per
     * call. Events that do fire go through {@link #invalidate} instead and are
     * re-read immediately. An unchanged re-read is dropped by its content hash, so
     * verification costs nothing on the wire.
     *
     * @param tick the server tick, used to age held sections
     * @return the number of columns queued for snapshotting
     */
    public synchronized int retarget(Collection<SectionPos> want, long tick) {
        return retarget(want, java.util.Collections.<ColumnPos>emptyList(), tick);
    }

    /**
     * @param hot columns a player currently occupies, which are re-read on
     *            {@link #DEFAULT_HOT_INTERVAL_TICKS} rather than the general
     *            staleness interval. Bounded by its own budget, so this cannot
     *            starve the sweep or exceed the sampler's per-tick ceiling.
     */
    public synchronized int retarget(Collection<SectionPos> want, Collection<ColumnPos> hot, long tick) {
        if (!enabled) return 0;
        retargeted = true;
        tickNow = tick;
        wanted.clear();
        int room = MAX_SECTIONS;
        for (SectionPos p : want) {
            if (room-- <= 0) break;
            wanted.add(p);
        }
        pendingColumns.clear();
        // Sections the engine does not have at all come first.
        for (SectionPos p : wanted) {
            if (!held.containsKey(p)) pendingColumns.add(column(p));
        }
        for (Iterator<Map.Entry<SectionPos, Held>> it = held.entrySet().iterator(); it.hasNext(); ) {
            SectionPos p = it.next().getKey();
            if (wanted.contains(p)) continue;
            it.remove();
            outbox.remove(p);
            deltas.remove(p);
            dropped.add(p);
        }
        // The ground under people's feet first: it is both the most likely to
        // have been changed by something that fires no event, and the only
        // place where believing stale terrain produces a verdict.
        if (hotIntervalTicks > 0 && hotColumnsPerRetarget > 0) {
            int hotBudget = hotColumnsPerRetarget;
            for (ColumnPos c : hot) {
                if (hotBudget <= 0) break;
                Long at = columnVerified.get(c);
                if (at != null && tick - at.longValue() < hotIntervalTicks) continue;
                if (pendingColumns.add(c)) hotBudget--;
            }
        }
        // Then a bounded slice of the stalest held sections. `wanted` is in the
        // sampler's ring order, so this verifies nearest-to-player first too.
        int budget = verifyColumnsPerRetarget;
        if (budget > 0 && verifyIntervalTicks > 0) {
            for (SectionPos p : wanted) {
                if (budget <= 0) break;
                Held h = held.get(p);
                if (h == null || tick - h.verifiedAtTick < verifyIntervalTicks) continue;
                if (pendingColumns.add(column(p))) budget--;
            }
        }
        // Forget columns nothing wants any more, so this map tracks the mirror
        // rather than growing with every place a player has ever stood.
        if (!columnVerified.isEmpty()) {
            Set<ColumnPos> live = new HashSet<ColumnPos>();
            for (SectionPos p : wanted) live.add(column(p));
            columnVerified.keySet().retainAll(live);
        }
        return pendingColumns.size();
    }

    /** Retargets at tick 0; the staleness sweep is inert without a advancing clock. */
    public int retarget(Collection<SectionPos> want) { return retarget(want, 0L); }

    /** Tunes the staleness backstop. 0 for either disables re-verification. */
    public synchronized void setVerification(long intervalTicks, int columnsPerRetarget) {
        verifyIntervalTicks = intervalTicks;
        verifyColumnsPerRetarget = columnsPerRetarget;
    }

    /** Tunes the under-foot sweep. 0 for either disables it. */
    public synchronized void setHotVerification(long intervalTicks, int columnsPerRetarget) {
        hotIntervalTicks = intervalTicks;
        hotColumnsPerRetarget = columnsPerRetarget;
    }

    public static ColumnPos column(SectionPos p) { return new ColumnPos(p.getDimension(), p.getX(), p.getZ()); }

    /** Up to {@code budget} columns to snapshot this tick; they leave the queue. */
    public synchronized List<ColumnPos> nextColumns(int budget) {
        List<ColumnPos> out = new ArrayList<ColumnPos>();
        if (!enabled || budget <= 0) return out;
        Iterator<ColumnPos> it = pendingColumns.iterator();
        while (it.hasNext() && out.size() < budget) {
            out.add(it.next());
            it.remove();
        }
        return out;
    }

    /** Section coordinates the sampler should read out of the given column. */
    public synchronized List<SectionPos> wantedIn(ColumnPos c) {
        List<SectionPos> out = new ArrayList<SectionPos>();
        for (SectionPos p : wanted) {
            if (p.getX() == c.x && p.getZ() == c.z && p.getDimension().equals(c.dimension)) out.add(p);
        }
        return out;
    }

    /**
     * Stamps the point in the change stream a snapshot is about to be taken at.
     * Call this on the thread that owns the chunk, immediately before reading the
     * world, and pass the result to {@link #acceptSection}.
     */
    public synchronized long snapshotToken() { return changeSeq; }

    /**
     * Records a freshly read section. Unchanged content is dropped, so a
     * re-snapshot of untouched terrain costs nothing on the wire.
     *
     * @param token the value {@link #snapshotToken()} returned before the world was read
     * @return true if the section was queued for sending
     */
    public synchronized boolean acceptSection(SectionPos pos, SectionData data, long token) {
        return acceptSection(pos, data, token, "");
    }

    /** @param biome name for the section's centre, passed straight through. */
    public synchronized boolean acceptSection(SectionPos pos, SectionData data, long token, String biome) {
        if (!enabled || !wanted.contains(pos)) return false;
        Held prev = held.get(pos);
        long h = data.contentHash();
        // Recorded before the unchanged-content shortcut below: a column that
        // never changes was still looked at, and forgetting that would leave it
        // eligible for the under-foot sweep on every retarget for ever.
        columnVerified.put(column(pos), Long.valueOf(tickNow));
        // Changes up to the token are already baked into the bytes that were read;
        // anything newer happened after the snapshot and must still be sent.
        LinkedHashMap<Integer, Change> queued = deltas.get(pos);
        if (queued != null) {
            for (Iterator<Map.Entry<Integer, Change>> it = queued.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue().seq <= token) it.remove();
            }
            if (queued.isEmpty()) deltas.remove(pos);
        }
        // Mark it verified even when the content is identical, or a section that
        // never changes would be re-read on every sweep forever.
        if (prev != null) {
            prev.verifiedAtTick = tickNow;
            if (prev.hash == h && !outbox.containsKey(pos)) return false;
            prev.hash = h;
        } else {
            held.put(pos, new Held(h, tickNow));
        }
        outbox.put(pos, data.toProto(pos, biome));
        return true;
    }

    /** Accepts a snapshot taken after every change queued so far. */
    public boolean acceptSection(SectionPos pos, SectionData data) {
        return acceptSection(pos, data, Long.MAX_VALUE);
    }

    /** A block changed. Ignored for sections the engine does not hold. */
    public synchronized void recordChange(SectionPos pos, int offset, String block) {
        if (!enabled) return;
        if (offset < 0 || offset >= SectionData.BLOCKS) return;
        if (!held.containsKey(pos)) return;
        LinkedHashMap<Integer, Change> m = deltas.get(pos);
        if (m == null) {
            if (deltas.size() >= MAX_SECTIONS) { droppedChanges++; return; }
            m = new LinkedHashMap<Integer, Change>();
            deltas.put(pos, m);
        }
        if (m.size() >= MAX_DELTAS && !m.containsKey(Integer.valueOf(offset))) { droppedChanges++; return; }
        m.put(Integer.valueOf(offset), new Change(++changeSeq, block == null ? "" : block));
    }

    /**
     * Forgets a section so the next retarget re-reads it. Used where an event says
     * a section changed but not cheaply what it changed to — pistons, liquid flow,
     * block physics — rather than guessing a state that would poison the mirror.
     */
    public synchronized void invalidate(SectionPos pos) {
        if (!enabled) return;
        if (held.remove(pos) != null) {
            outbox.remove(pos);
            deltas.remove(pos);
        }
    }

    public synchronized boolean hasWork() {
        return enabled && (fullSyncPending || !outbox.isEmpty() || !deltas.isEmpty() || !dropped.isEmpty());
    }

    /**
     * Packages what has accumulated into frames: sections first, then the delta
     * frame, so the engine applies a snapshot before the changes that followed it.
     * Sections beyond {@code maxBytes} stay queued for the next call.
     */
    public synchronized List<UpStream> drain(long nowMs, long tick, int maxBytes) {
        List<UpStream> frames = new ArrayList<UpStream>();
        if (!enabled) return frames;
        if (maxBytes <= 0) maxBytes = DEFAULT_MAX_FRAME_BYTES;

        boolean start = fullSyncPending;
        fullSyncPending = false;
        int bytes = 0;
        WorldChunk.Builder chunk = null;
        for (Iterator<Map.Entry<SectionPos, Section>> it = outbox.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SectionPos, Section> e = it.next();
            int size = e.getValue().getSerializedSize();
            if (chunk != null && bytes + size > maxBytes) break;
            if (chunk == null) chunk = WorldChunk.newBuilder();
            chunk.addSections(e.getValue());
            bytes += size;
            it.remove();
        }
        // A full sync is complete once every wanted column has been snapshotted and
        // every section it produced has gone out.
        boolean done = syncing && retargeted && pendingColumns.isEmpty() && outbox.isEmpty();
        if (chunk == null && (start || done || boundsPending)) chunk = WorldChunk.newBuilder();
        if (chunk != null) {
            if (start) chunk.setFullSyncStart(true);
            if (done) { chunk.setFullSyncDone(true); syncing = false; }
            if (boundsPending) { chunk.addAllBounds(bounds.values()); boundsPending = false; }
            frames.add(UpStream.newBuilder().setWorldChunk(chunk).build());
        }

        if (!deltas.isEmpty() || !dropped.isEmpty()) {
            WorldDelta.Builder d = WorldDelta.newBuilder().setClientTimeMs(nowMs).setTick(tick);
            for (Map.Entry<SectionPos, LinkedHashMap<Integer, Change>> e : deltas.entrySet()) {
                SectionChanges.Builder sc = SectionChanges.newBuilder().setSection(e.getKey());
                for (Map.Entry<Integer, Change> c : e.getValue().entrySet()) {
                    sc.addChanges(BlockChange.newBuilder().setOffset(c.getKey().intValue()).setBlock(c.getValue().block));
                }
                d.addSections(sc);
            }
            d.addAllDropped(dropped);
            deltas.clear();
            dropped.clear();
            frames.add(UpStream.newBuilder().setWorldDelta(d).build());
        }
        return frames;
    }

    /**
     * Records the owning-thread cost of one chunk snapshot. This is the only part
     * of world streaming that runs on a server thread, so it is the number to watch
     * when judging what the feature costs a customer.
     */
    public synchronized void recordSnapshot(long nanos) {
        snapshotNanos += nanos;
        snapshotCount++;
    }

    /** Mean microseconds per chunk snapshot on the owning thread, 0 if none taken. */
    public synchronized long snapshotMicros() {
        return snapshotCount == 0 ? 0 : (snapshotNanos / snapshotCount) / 1000;
    }

    public synchronized long snapshotCount() { return snapshotCount; }

    public synchronized int heldSections() { return held.size(); }
    public synchronized int pendingColumnCount() { return pendingColumns.size(); }
    public synchronized int outboxSize() { return outbox.size(); }
    public synchronized boolean syncing() { return syncing; }
    public synchronized long droppedChanges() { return droppedChanges; }
}
