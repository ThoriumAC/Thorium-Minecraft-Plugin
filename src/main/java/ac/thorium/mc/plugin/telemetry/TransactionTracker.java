package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.TransactionCause;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Allocates transaction ids and matches the client's echoes back to what was sent.
 * Ids live in a reserved negative range that fits a 1.16 window-confirmation short and
 * cannot collide with the server's own (positive) pings. Pure Java; no Bukkit.
 */
public final class TransactionTracker {
    /** Inclusive id range. ~31k ids cycle per player; stale entries are pruned. */
    public static final int ID_MIN = -32000, ID_MAX = -1000;
    private static final long STALE_NANOS = 30_000_000_000L;

    public static final class Pending {
        public final TransactionCause cause; public final long sentNanos;
        Pending(TransactionCause cause, long sentNanos) { this.cause = cause; this.sentNanos = sentNanos; }
    }

    public static final class Ack {
        public final TransactionCause cause; public final long rttMs;
        Ack(TransactionCause cause, long rttMs) { this.cause = cause; this.rttMs = rttMs; }
    }

    private final LongSupplier nanos;
    private final AtomicInteger next = new AtomicInteger(ID_MAX);
    private final Map<UUID, Map<Integer, Pending>> pending = new ConcurrentHashMap<>();

    public TransactionTracker(LongSupplier nanos) { this.nanos = nanos; }

    public static boolean isOurs(int id) { return id >= ID_MIN && id <= ID_MAX; }

    /** Reserves an id for a player and records what it stands for. */
    public int send(UUID player, TransactionCause cause) {
        int id = next.updateAndGet(v -> v <= ID_MIN ? ID_MAX : v - 1);
        Map<Integer, Pending> m = pending.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        long now = nanos.getAsLong();
        if (m.size() > 256) prune(m, now);
        m.put(id, new Pending(cause, now));
        return id;
    }

    /** Matches an echoed id; null when it is not one of ours (or already answered). */
    public Ack ack(UUID player, int id) {
        if (!isOurs(id)) return null;
        Map<Integer, Pending> m = pending.get(player);
        if (m == null) return null;
        Pending p = m.remove(id);
        if (p == null) return null;
        return new Ack(p.cause, Math.max(0, (nanos.getAsLong() - p.sentNanos) / 1_000_000L));
    }

    public void forget(UUID player) { pending.remove(player); }

    public int pending(UUID player) { Map<Integer, Pending> m = pending.get(player); return m == null ? 0 : m.size(); }

    private static void prune(Map<Integer, Pending> m, long now) {
        for (Iterator<Map.Entry<Integer, Pending>> it = m.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().sentNanos > STALE_NANOS) it.remove();
        }
    }
}
