package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.plugin.capture.BukkitEvents;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.proto.ServerMeta;
import org.bukkit.entity.Player;

public final class MetaBuilder {
    private MetaBuilder() {}

    public static ServerMeta build(Player p, ServerCompat compat) {
        return ServerMeta.newBuilder()
                .setGamemode(Names.gamemode(p.getGameMode().name()))
                .setDimension(Names.dimension(p.getWorld().getEnvironment().name()))
                .setWorld(p.getWorld().getName())
                .setEntityId(p.getEntityId())
                .setProtocolVersion(BukkitEvents.protocol(p))
                .setPingMs(compat.ping(p))
                .setDead(p.isDead())
                .setSleeping(p.isSleeping())
                .build();
    }
}
