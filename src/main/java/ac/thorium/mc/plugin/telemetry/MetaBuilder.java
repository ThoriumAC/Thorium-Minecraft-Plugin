package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.ServerMeta;

public final class MetaBuilder {
    private MetaBuilder() {}

    public static ServerMeta build(PlayerContext c, boolean serverMoved) {
        return ServerMeta.newBuilder()
                .setGamemode(c.gamemode).setDimension(c.dimension).setWorld(c.world).setPingMs(c.pingMs).setTps(c.tps).setServerMoved(serverMoved)
                .setInVehicle(c.inVehicle).setVehicleType(c.vehicleType).setGliding(c.gliding).setRiptiding(c.riptiding).setInWater(c.inWater).setInLava(c.inLava)
                .setOnIce(c.onIce).setOnLadder(c.onLadder).setInWeb(c.inWeb).setOnSlime(c.onSlime).setOnSoulSand(c.onSoulSand)
                .setBlockBelowSolid(c.blockBelowSolid).setBlockAboveSolid(c.blockAboveSolid).setBlockBelowLiquid(c.blockBelowLiquid).setLevitation(c.levitation).setSlowFalling(c.slowFalling)
                .setSpeedAmplifier(c.speedAmplifier).setJumpAmplifier(c.jumpAmplifier).setFlyingAllowed(c.flyingAllowed).setIsFlying(c.isFlying)
                .setHasElytra(c.hasElytra).setFrostWalker(c.frostWalker).setDead(c.dead).setSleeping(c.sleeping)
                .setNearWall(c.nearWall).setPushableNearby(c.pushableNearby)
                .build();
    }
}
