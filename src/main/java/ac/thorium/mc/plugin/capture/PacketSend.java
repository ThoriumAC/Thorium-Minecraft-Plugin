package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.proto.TransactionCause;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import org.bukkit.entity.Player;

/**
 * Watches outbound packets that move the player and streams a transaction marker
 * right behind each one (see TransactionSample). MONITOR priority, never cancels,
 * never mutates packets. The marker is written in a post-send task so it sits
 * strictly after the state-changing packet on the wire.
 */
public final class PacketSend extends PacketListenerAbstract {
    private final Telemetry telemetry;
    private final ErrorGate gate;

    public PacketSend(Telemetry telemetry, ErrorGate gate) {
        super(PacketListenerPriority.MONITOR);
        this.telemetry = telemetry; this.gate = gate;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.ENTITY_VELOCITY && type != PacketType.Play.Server.EXPLOSION && type != PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) return;
        Object po = event.getPlayer();
        if (!(po instanceof Player)) return;
        Player p = (Player) po;
        User user = event.getUser();
        gate.run("send:" + type, () -> dispatch(type, event, p, user));
    }

    private void dispatch(PacketTypeCommon type, PacketSendEvent event, Player p, User user) {
        if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity w = new WrapperPlayServerEntityVelocity(event);
            if (user == null || w.getEntityId() != user.getEntityId()) return;
            Vector3d v = w.getVelocity();
            if (v == null || (v.getX() == 0 && v.getY() == 0 && v.getZ() == 0)) return;
            event.getPostTasks().add(() -> gate.run("send:velocity-tx", () -> telemetry.transaction(p, TransactionCause.TRANSACTION_CAUSE_VELOCITY)));
        } else if (type == PacketType.Play.Server.EXPLOSION) {
            WrapperPlayServerExplosion w = new WrapperPlayServerExplosion(event);
            if (!pushes(w)) return;
            event.getPostTasks().add(() -> gate.run("send:explosion-tx", () -> telemetry.transaction(p, TransactionCause.TRANSACTION_CAUSE_EXPLOSION)));
        } else {
            WrapperPlayServerPlayerPositionAndLook w = new WrapperPlayServerPlayerPositionAndLook(event);
            int id = w.getTeleportId();
            event.getPostTasks().add(() -> gate.run("send:teleport-tx", () -> telemetry.teleportSent(p, id)));
        }
    }

    /** 1.21.2+ carries an explicit player motion; older versions a knockback vector. Either non-zero means a push. */
    private static boolean pushes(WrapperPlayServerExplosion w) {
        try {
            Vector3d k = w.getKnockback();
            if (k != null && (k.getX() != 0 || k.getY() != 0 || k.getZ() != 0)) return true;
        } catch (Throwable ignored) {}
        try {
            Vector3f m = w.getPlayerMotion();
            return m != null && (m.getX() != 0 || m.getY() != 0 || m.getZ() != 0);
        } catch (Throwable ignored) { return false; }
    }
}
