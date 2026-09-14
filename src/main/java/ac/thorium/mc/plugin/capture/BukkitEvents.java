package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.telemetry.Names;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.*;
import org.bukkit.util.Vector;

import java.util.Locale;

public final class BukkitEvents implements Listener {
    private final Telemetry telemetry;
    private final PacketCapture capture;   // null when packetevents failed to load
    private final ErrorGate gate;
    private final boolean sendIp;

    public BukkitEvents(Telemetry telemetry, PacketCapture capture, ErrorGate gate, boolean sendIp) {
        this.telemetry = telemetry; this.capture = capture; this.gate = gate; this.sendIp = sendIp;
    }

    private static int protocol(Player p) {
        try {
            User u = PacketEvents.getAPI().getPlayerManager().getUser(p);
            return u == null ? 0 : u.getClientVersion().getProtocolVersion();
        } catch (Throwable t) { return 0; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        gate.run("event:join", () -> {
            Player p = e.getPlayer();
            telemetry.track(p);
            telemetry.event(p, EventFactory.join(EventFactory.hostAddress(p.getAddress(), sendIp), protocol(p), "", Names.gamemode(p.getGameMode().name())));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        gate.run("event:quit", () -> {
            Player p = e.getPlayer();
            telemetry.event(p, EventFactory.quit(""));
            telemetry.untrack(p);
            if (capture != null) capture.forget(p.getUniqueId());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        gate.run("event:teleport", () -> {
            Player p = e.getPlayer();
            Location to = e.getTo();
            if (to == null) return;
            telemetry.markMoved(p);
            telemetry.event(p, EventFactory.teleport(to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch(), Names.teleportCause(e.getCause() == null ? null : e.getCause().name())));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        gate.run("event:velocity", () -> {
            Player p = e.getPlayer();
            Vector v = e.getVelocity();
            telemetry.markMoved(p);
            telemetry.event(p, EventFactory.velocity(v.getX(), v.getY(), v.getZ()));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        gate.run("event:damage", () -> {
            Player p = (Player) e.getEntity();
            if (e instanceof EntityDamageByEntityEvent) telemetry.markMoved(p);
            String cause = e.getCause() == null ? "unknown" : e.getCause().name().toLowerCase(Locale.ROOT);
            telemetry.event(p, EventFactory.damage(cause, e.getFinalDamage(), p.getFallDistance()));
        });
    }

    /** Explosion knockback reaches players inside the explosion packet, never as a velocity packet, so no
     *  PlayerVelocityEvent fires. Estimate the push for nearby players so the engine exempts the burst
     *  and the Velocity check has something to compare against. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { explosion(e.getLocation(), e.getYield()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { explosion(e.getBlock().getLocation(), e.getYield()); }

    private void explosion(Location at, float yield) {
        gate.run("event:explosion", () -> {
            double radius = Math.max(4.0, 2.0 * Math.max(1f, yield * 4f));   // yield ≈ power/4 for TNT
            for (Player p : at.getWorld().getPlayers()) {
                Location pl = p.getLocation();
                double dx = pl.getX() - at.getX(), dy = pl.getY() + 0.9 - at.getY(), dz = pl.getZ() - at.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > radius || dist < 1e-6) continue;
                double k = (1.0 - dist / radius) / dist;   // vanilla: (1 - d/r) along the unit vector, before exposure scaling
                telemetry.markMoved(p);
                telemetry.event(p, EventFactory.velocity(dx * k, dy * k, dz * k));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        gate.run("event:gamemode", () -> telemetry.event(e.getPlayer(), EventFactory.gamemode(Names.gamemode(e.getNewGameMode().name()))));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        gate.run("event:world", () -> {
            Player p = e.getPlayer();
            telemetry.markMoved(p);
            telemetry.event(p, EventFactory.world(Names.dimension(p.getWorld().getEnvironment().name())));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        gate.run("event:respawn", () -> {
            telemetry.markMoved(e.getPlayer());
            telemetry.event(e.getPlayer(), EventFactory.respawn());
        });
    }
}
