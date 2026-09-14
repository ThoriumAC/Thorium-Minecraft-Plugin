package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.Batch;
import ac.thorium.mc.proto.PlayerBatch;
import ac.thorium.mc.proto.PlayerRef;
import ac.thorium.mc.proto.Sample;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Per-player sample queues, drained into one Batch per flush. Producers are netty/player threads; the drainer is the flush task. */
public final class SampleBuffer {
    private static final class Queue { PlayerRef ref; final ArrayDeque<Sample> samples = new ArrayDeque<>(); }

    private final int maxPerPlayer;
    private final Map<UUID, Queue> queues = new LinkedHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private volatile Set<String> enabled = Collections.emptySet();

    public SampleBuffer(int maxSamplesPerPlayer) { this.maxPerPlayer = maxSamplesPerPlayer; }

    /** Empty/null = all categories. */
    public void setEnabledCategories(Collection<String> cats) {
        enabled = cats == null || cats.isEmpty() ? Collections.<String>emptySet() : new HashSet<>(cats);
    }

    /** false only when the category filter rejects the sample; eviction of the oldest still returns true and bumps dropped(). */
    public boolean add(UUID uuid, PlayerRef ref, Sample s) {
        Set<String> en = enabled;
        if (!en.isEmpty() && !en.contains(category(s))) return false;
        synchronized (this) {
            Queue q = queues.get(uuid);
            if (q == null) { q = new Queue(); queues.put(uuid, q); }
            q.ref = ref;
            if (q.samples.size() >= maxPerPlayer) { q.samples.pollFirst(); dropped.incrementAndGet(); }
            q.samples.addLast(s);
        }
        return true;
    }

    public synchronized Batch drain(long nowMs) {
        if (queues.isEmpty()) return null;
        Batch.Builder b = Batch.newBuilder().setSentAtMs(nowMs);
        for (Queue q : queues.values()) {
            if (q.samples.isEmpty()) continue;
            b.addPlayers(PlayerBatch.newBuilder().setPlayer(q.ref).addAllSamples(new ArrayList<>(q.samples)));
        }
        queues.clear();
        return b.getPlayersCount() == 0 ? null : b.build();
    }

    public synchronized int pending() { int n = 0; for (Queue q : queues.values()) n += q.samples.size(); return n; }
    public long dropped() { return dropped.get(); }
    public synchronized void clear() { queues.clear(); }
    public synchronized void remove(UUID uuid) { queues.remove(uuid); }

    public static String category(Sample s) {
        switch (s.getKindCase()) {
            case MOVEMENT: case ROTATION: case TRANSACTION: return "movement";
            case COMBAT: return "combat";
            case BLOCK: return "block";
            case INVENTORY: return "inventory";
            case CLIENT: return "client";
            default: return "other";
        }
    }
}
