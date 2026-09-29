package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.TransactionCause;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public final class TransactionTracker {
    public static final int ID_MIN = -32000, ID_MAX = -1000;
    private static final long STALE_NANOS = 30_000_000_000L;

    public static final class Pending {
        public final TransactionCause cause; public final long sentNanos; public final long fencesSeq;
        Pending(TransactionCause cause, long sentNanos, long fencesSeq) { this.cause = cause; this.sentNanos = sentNanos; this.fencesSeq = fencesSeq; }
    }

    public static final class Ack {
        public final TransactionCause cause; public final long rttMs; public final long fencesSeq;
        Ack(TransactionCause cause, long rttMs, long fencesSeq) { this.cause = cause; this.rttMs = rttMs; this.fencesSeq = fencesSeq; }
    }

    private final LongSupplier nanos;
    private final AtomicInteger next = new AtomicInteger(ID_MAX);
    private final Map<UUID, Map<Integer, Pending>> pending = new ConcurrentHashMap<>();

    public TransactionTracker(LongSupplier nanos) { this.nanos = nanos; }

    public static boolean isOurs(int id) { return id >= ID_MIN && id <= ID_MAX; }

    public int send(UUID player, TransactionCause cause) { return send(player, cause, 0L); }

    public int send(UUID player, TransactionCause cause, long fencesSeq) {
        int id = next.updateAndGet(v -> v <= ID_MIN ? ID_MAX : v - 1);
        Map<Integer, Pending> m = pending.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        long now = nanos.getAsLong();
        if (m.size() > 256) prune(m, now);
        m.put(id, new Pending(cause, now, fencesSeq));
        return id;
    }

    public Ack ack(UUID player, int id) {
        if (!isOurs(id)) return null;
        Map<Integer, Pending> m = pending.get(player);
        if (m == null) return null;
        Pending p = m.remove(id);
        if (p == null) return null;
        return new Ack(p.cause, Math.max(0, (nanos.getAsLong() - p.sentNanos) / 1_000_000L), p.fencesSeq);
    }

    public void forget(UUID player) { pending.remove(player); }

    public int pending(UUID player) { Map<Integer, Pending> m = pending.get(player); return m == null ? 0 : m.size(); }

    private static void prune(Map<Integer, Pending> m, long now) {
        for (Iterator<Map.Entry<Integer, Pending>> it = m.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().sentNanos > STALE_NANOS) it.remove();
        }
    }
}
