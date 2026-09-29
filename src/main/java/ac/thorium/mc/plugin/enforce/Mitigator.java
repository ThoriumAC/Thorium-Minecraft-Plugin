package ac.thorium.mc.plugin.enforce;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.telemetry.Names;
import ac.thorium.mc.proto.*;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

public final class Mitigator extends PacketListenerAbstract implements Consumer<Mitigate> {
    static final int SETBACK_COOLDOWN_TICKS = 5;
    static final int RESYNC_COOLDOWN_TICKS = 10;

    static final class Windows {
        private final Map<UUID, Long> cancelUntil = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastSetback = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastResync = new ConcurrentHashMap<>();

        void open(UUID p, long tick, int ticks) { cancelUntil.put(p, tick + ticks); }
        boolean shouldCancel(UUID p, long tick) { Long u = cancelUntil.get(p); return u != null && tick <= u; }
        void teleportConfirmed(UUID p) { cancelUntil.remove(p); }
        boolean allowSetback(UUID p, long tick) { return allow(lastSetback, p, tick, SETBACK_COOLDOWN_TICKS); }
        boolean allowResync(UUID p, long tick) { return allow(lastResync, p, tick, RESYNC_COOLDOWN_TICKS); }
        private static boolean allow(Map<UUID, Long> last, UUID p, long tick, int cooldown) {
            Long l = last.get(p);
            if (l != null && tick - l < cooldown) return false;
            last.put(p, tick); return true;
        }
        void forget(UUID p) { cancelUntil.remove(p); lastSetback.remove(p); lastResync.remove(p); }
    }

    private final Scheduler sched;
    private final ServerCompat compat;
    private final ErrorGate gate;
    private final PluginConfig cfg;
    private final LongSupplier tick;
    private final Logger log;
    private final Windows windows = new Windows();

    public Mitigator(Scheduler sched, ServerCompat compat, ErrorGate gate, PluginConfig cfg, LongSupplier tick, Logger log) {
        super(PacketListenerPriority.NORMAL);
        this.sched = sched; this.compat = compat; this.gate = gate; this.cfg = cfg; this.tick = tick; this.log = log;
    }

    @Override
    public void accept(Mitigate m) {
        if (!cfg.mitigate || !cfg.enforce) return;
        UUID id = Names.uuid(m.getPlayer().getUuid());
        Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p == null) return;
        long now = tick.getAsLong();
        switch (m.getKindCase()) {
            case SETBACK: {
                if (!windows.allowSetback(id, now)) return;
                Setback s = m.getSetback();
                sched.runForPlayer(p, () -> gate.run("mitigate:setback", () -> {
                    Location cur = p.getLocation();
                    float yaw = s.getKeepLook() ? cur.getYaw() : s.getLook().getYaw();
                    float pitch = s.getKeepLook() ? cur.getPitch() : s.getLook().getPitch();
                    p.teleport(new Location(p.getWorld(), s.getPosition().getX(), s.getPosition().getY(), s.getPosition().getZ(), yaw, pitch), PlayerTeleportEvent.TeleportCause.PLUGIN);
                }));
                break;
            }
            case CANCEL: windows.open(id, now, m.getCancel().getTicks()); break;
            case BLOCKS: {
                if (!windows.allowResync(id, now)) return;
                ResyncBlocks r = m.getBlocks();
                sched.runForChunk(p.getWorld(), r.getSx(), r.getSz(), () -> gate.run("mitigate:resync", () -> compat.resendSection(p, r.getSx(), r.getSy(), r.getSz())));
                break;
            }
            default: break;
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon t = event.getPacketType();
        Object po = event.getPlayer();
        if (!(po instanceof Player)) return;
        UUID id = ((Player) po).getUniqueId();
        if (t == PacketType.Play.Client.TELEPORT_CONFIRM) { windows.teleportConfirmed(id); return; }
        if (t == PacketType.Play.Client.PLAYER_POSITION || t == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || t == PacketType.Play.Client.PLAYER_FLYING || t == PacketType.Play.Client.PLAYER_ROTATION || t == PacketType.Play.Client.VEHICLE_MOVE) {
            if (windows.shouldCancel(id, tick.getAsLong())) event.setCancelled(true);
        }
    }

    public void forget(UUID id) { windows.forget(id); }
}
