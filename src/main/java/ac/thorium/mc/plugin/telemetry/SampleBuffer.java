package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.*;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SampleBuffer {
    private static final class Slot {
        final PlayerRing ring; volatile PlayerRef ref; volatile ServerMeta meta; long seq;
        Slot(int cap) { ring = new PlayerRing(cap); }
    }

    private final int capacity;
    private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();
    private volatile Set<String> enabled = Collections.emptySet();

    public SampleBuffer(int capacityPerPlayer) { this.capacity = capacityPerPlayer; }

    private volatile boolean relativeTimes;

    public void setRelativeTimes(boolean on) { relativeTimes = on; }

    public void setEnabledCategories(Collection<String> cats) {
        enabled = cats == null || cats.isEmpty() ? Collections.<String>emptySet() : new HashSet<>(cats);
    }

    /**
     * Stamps the player's next seq and queues the sample, as one step so seqs
     * stay in queue order across the threads that produce for a player.
     * Returns the seq, or -1 when the sample was filtered or the ring was full.
     */
    public long add(UUID uuid, PlayerRef ref, Sample.Builder s) {
        Set<String> en = enabled;
        if (!en.isEmpty() && !en.contains(category(s.getKindCase()))) return -1;
        Slot slot = slots.computeIfAbsent(uuid, k -> new Slot(capacity));
        slot.ref = ref;
        synchronized (slot) {
            long seq = ++slot.seq & 0xFFFFFFFFL;
            return slot.ring.offer(s.setSeq((int) seq)) ? seq : -1;
        }
    }

    public void meta(UUID uuid, ServerMeta meta) { Slot slot = slots.get(uuid); if (slot != null) slot.meta = meta; }

    public Batch drain(long nowMs, long serverTick, double tps, double mspt) {
        Batch.Builder b = null;
        for (Map.Entry<UUID, Slot> e : slots.entrySet()) {
            Slot slot = e.getValue();
            if (slot.ring.isEmpty()) continue;
            PlayerBatch.Builder pb = PlayerBatch.newBuilder().setPlayer(slot.ref);
            if (slot.ring.drainTo(pb, relativeTimes) == 0) continue;
            ServerMeta m = slot.meta;
            if (m != null) pb.setMeta(m);
            if (b == null) b = Batch.newBuilder().setSentAtMs(nowMs).setServerTick(serverTick).setTps(tps).setMspt(mspt);
            b.addPlayers(pb);
        }
        return b == null ? null : b.build();
    }

    public int pending() { int n = 0; for (Slot s : slots.values()) if (!s.ring.isEmpty()) n++; return n; }
    public long dropped() { long n = 0; for (Slot s : slots.values()) n += s.ring.dropped(); return n; }
    public void clear() { for (Slot s : slots.values()) s.ring.drainTo(PlayerBatch.newBuilder()); }
    public void remove(UUID uuid) { slots.remove(uuid); }

    public static String category(Sample.KindCase k) {
        switch (k) {
            case MOVEMENT: case ROTATION: case TRANSACTION: case INPUT: case TICK_END: case ENTITY_ACTION: case ABILITIES: case VEHICLE_MOVE: case PADDLE: case KEEP_ALIVE: return "movement";
            case COMBAT: case USE_ITEM: case HELD_SLOT: return "combat";
            case BLOCK: return "block";
            case INVENTORY: return "inventory";
            case CLIENT: case SETTINGS: case CLIENT_STATUS: case SPECTATE: case TEXT: case OTHER: return "client";
            case OUTBOUND: return "outbound";
            default: return "other";
        }
    }
}
