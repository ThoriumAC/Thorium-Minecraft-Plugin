package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.*;

import java.net.InetSocketAddress;

public final class EventFactory {
    private EventFactory() {}

    public static PlayerEvent.Builder join(String ip, int protocolVersion, String brand, int gamemode) {
        return PlayerEvent.newBuilder().setJoin(Join.newBuilder().setIp(ip == null ? "" : ip).setProtocolVersion(protocolVersion).setBrand(brand == null ? "" : brand).setGamemode(gamemode));
    }
    public static PlayerEvent.Builder quit(String reason) { return PlayerEvent.newBuilder().setQuit(Quit.newBuilder().setReason(reason == null ? "" : reason)); }
    public static PlayerEvent.Builder teleport(double x, double y, double z, float yaw, float pitch, String cause) {
        return PlayerEvent.newBuilder().setTeleport(Teleport.newBuilder().setTo(Vec3.newBuilder().setX(x).setY(y).setZ(z)).setLook(Look.newBuilder().setYaw(yaw).setPitch(pitch)).setCause(cause == null ? "" : cause));
    }
    public static PlayerEvent.Builder velocity(double vx, double vy, double vz) {
        return PlayerEvent.newBuilder().setVelocity(Velocity.newBuilder().setVelocity(Vec3.newBuilder().setX(vx).setY(vy).setZ(vz)));
    }
    public static PlayerEvent.Builder damage(String cause, double amount, double fallDistance) {
        return PlayerEvent.newBuilder().setDamage(Damage.newBuilder().setCause(cause == null ? "" : cause).setAmount(amount).setFallDistance(fallDistance));
    }
    public static PlayerEvent.Builder gamemode(int gamemode) { return PlayerEvent.newBuilder().setGamemode(GamemodeChange.newBuilder().setGamemode(gamemode)); }
    public static PlayerEvent.Builder world(String dimension) { return PlayerEvent.newBuilder().setWorld(WorldChange.newBuilder().setDimension(dimension == null ? "" : dimension)); }
    public static PlayerEvent.Builder respawn() { return PlayerEvent.newBuilder().setRespawn(Respawn.newBuilder()); }

    public static String hostAddress(InetSocketAddress addr, boolean allowed) {
        if (!allowed || addr == null || addr.getAddress() == null) return "";
        return addr.getAddress().getHostAddress();
    }
}
