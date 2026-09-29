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

public final class WorldMirror {
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
    private static final int MAX_SECTIONS = 8192;
    private static final int MAX_DELTAS = 16384;
    private static final int DEFAULT_MAX_FRAME_BYTES = 192 * 1024;
    public static final long DEFAULT_VERIFY_INTERVAL_TICKS = 2400;
    public static final int DEFAULT_VERIFY_COLUMNS = 8;
    public static final long DEFAULT_HOT_INTERVAL_TICKS = 60;
    public static final int DEFAULT_HOT_COLUMNS = 4;

    private volatile boolean enabled;
    private volatile int radiusChunks = DEFAULT_RADIUS_CHUNKS;
    private volatile int columnsPerTick = DEFAULT_COLUMNS_PER_TICK;

    private static final class Held {
        long hash;
        long verifiedAtTick;
        Held(long hash, long tick) { this.hash = hash; this.verifiedAtTick = tick; }
    }

    private final Map<SectionPos, Held> held = new LinkedHashMap<SectionPos, Held>();
    private final Map<SectionPos, Section> outbox = new LinkedHashMap<SectionPos, Section>();
    private final Map<SectionPos, LinkedHashMap<Integer, Change>> deltas =
            new LinkedHashMap<SectionPos, LinkedHashMap<Integer, Change>>();
    private final LinkedHashSet<ColumnPos> pendingColumns = new LinkedHashSet<ColumnPos>();
    private final Set<SectionPos> wanted = new HashSet<SectionPos>();
    private final List<SectionPos> dropped = new ArrayList<SectionPos>();

    private boolean fullSyncPending;
    private boolean syncing;
    private boolean retargeted;
    private long tickNow;
    private long verifyIntervalTicks = DEFAULT_VERIFY_INTERVAL_TICKS;
    private int verifyColumnsPerRetarget = DEFAULT_VERIFY_COLUMNS;
    private long hotIntervalTicks = DEFAULT_HOT_INTERVAL_TICKS;
    private int hotColumnsPerRetarget = DEFAULT_HOT_COLUMNS;
    private final Map<ColumnPos, Long> columnVerified = new LinkedHashMap<ColumnPos, Long>();

    private final Map<String, WorldBounds> bounds = new LinkedHashMap<String, WorldBounds>();
    private boolean boundsPending;
    private long droppedChanges;
    private long changeSeq;
    private long snapshotNanos;
    private long snapshotCount;

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

    public synchronized void reset() {
        clearLocked();
        fullSyncPending = true;
        syncing = true;
    }

    public synchronized void bounds(String dimension, int minY, int maxY) {
        if (dimension == null || dimension.isEmpty() || maxY <= minY) return;
        WorldBounds b = WorldBounds.newBuilder().setDimension(dimension).setMinY(minY).setMaxY(maxY).build();
        WorldBounds had = bounds.get(dimension);
        if (b.equals(had)) return;
        bounds.put(dimension, b);
        boundsPending = true;
    }

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
        boundsPending = !bounds.isEmpty();
    }

    public synchronized int retarget(Collection<SectionPos> want, long tick) {
        return retarget(want, java.util.Collections.<ColumnPos>emptyList(), tick);
    }

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
        if (hotIntervalTicks > 0 && hotColumnsPerRetarget > 0) {
            int hotBudget = hotColumnsPerRetarget;
            for (ColumnPos c : hot) {
                if (hotBudget <= 0) break;
                Long at = columnVerified.get(c);
                if (at != null && tick - at.longValue() < hotIntervalTicks) continue;
                if (pendingColumns.add(c)) hotBudget--;
            }
        }
        int budget = verifyColumnsPerRetarget;
        if (budget > 0 && verifyIntervalTicks > 0) {
            for (SectionPos p : wanted) {
                if (budget <= 0) break;
                Held h = held.get(p);
                if (h == null || tick - h.verifiedAtTick < verifyIntervalTicks) continue;
                if (pendingColumns.add(column(p))) budget--;
            }
        }
        if (!columnVerified.isEmpty()) {
            Set<ColumnPos> live = new HashSet<ColumnPos>();
            for (SectionPos p : wanted) live.add(column(p));
            columnVerified.keySet().retainAll(live);
        }
        return pendingColumns.size();
    }

    public int retarget(Collection<SectionPos> want) { return retarget(want, 0L); }

    public synchronized void setVerification(long intervalTicks, int columnsPerRetarget) {
        verifyIntervalTicks = intervalTicks;
        verifyColumnsPerRetarget = columnsPerRetarget;
    }

    public synchronized void setHotVerification(long intervalTicks, int columnsPerRetarget) {
        hotIntervalTicks = intervalTicks;
        hotColumnsPerRetarget = columnsPerRetarget;
    }

    public static ColumnPos column(SectionPos p) { return new ColumnPos(p.getDimension(), p.getX(), p.getZ()); }

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

    public synchronized List<SectionPos> wantedIn(ColumnPos c) {
        List<SectionPos> out = new ArrayList<SectionPos>();
        for (SectionPos p : wanted) {
            if (p.getX() == c.x && p.getZ() == c.z && p.getDimension().equals(c.dimension)) out.add(p);
        }
        return out;
    }

    public synchronized long snapshotToken() { return changeSeq; }

    public synchronized boolean acceptSection(SectionPos pos, SectionData data, long token) {
        return acceptSection(pos, data, token, "");
    }

    public synchronized boolean acceptSection(SectionPos pos, SectionData data, long token, String biome) {
        if (!enabled || !wanted.contains(pos)) return false;
        Held prev = held.get(pos);
        long h = data.contentHash();
        columnVerified.put(column(pos), Long.valueOf(tickNow));
        LinkedHashMap<Integer, Change> queued = deltas.get(pos);
        if (queued != null) {
            for (Iterator<Map.Entry<Integer, Change>> it = queued.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue().seq <= token) it.remove();
            }
            if (queued.isEmpty()) deltas.remove(pos);
        }
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

    public boolean acceptSection(SectionPos pos, SectionData data) {
        return acceptSection(pos, data, Long.MAX_VALUE);
    }

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

    public synchronized void recordSnapshot(long nanos) {
        snapshotNanos += nanos;
        snapshotCount++;
    }

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
