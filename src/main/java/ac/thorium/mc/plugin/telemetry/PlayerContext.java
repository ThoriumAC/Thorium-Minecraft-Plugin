package ac.thorium.mc.plugin.telemetry;

/** Immutable server-side truth for one player, refreshed every tick on the player's thread and read from netty threads. */
public final class PlayerContext {
    public static final PlayerContext UNKNOWN = builder().pingMs(-1).tps(20.0).dimension("").build();

    public final int gamemode;
    public final String dimension;
    public final int pingMs;
    public final double tps;
    public final boolean inVehicle, gliding, riptiding, inWater, inLava, onIce, onLadder, inWeb, onSlime, onSoulSand,
            blockBelowSolid, blockAboveSolid, blockBelowLiquid, levitation, slowFalling, flyingAllowed, isFlying, hasElytra, frostWalker, dead, sleeping,
            nearWall, pushableNearby;
    public final int speedAmplifier, jumpAmplifier;
    public final double x, y, z;
    public final float yaw, pitch;
    public final long tick;

    private PlayerContext(Builder b) {
        gamemode = b.gamemode; dimension = b.dimension; pingMs = b.pingMs; tps = b.tps;
        inVehicle = b.inVehicle; gliding = b.gliding; riptiding = b.riptiding; inWater = b.inWater; inLava = b.inLava; onIce = b.onIce;
        onLadder = b.onLadder; inWeb = b.inWeb; onSlime = b.onSlime; onSoulSand = b.onSoulSand; blockBelowSolid = b.blockBelowSolid;
        blockAboveSolid = b.blockAboveSolid; blockBelowLiquid = b.blockBelowLiquid; levitation = b.levitation; slowFalling = b.slowFalling; flyingAllowed = b.flyingAllowed;
        isFlying = b.isFlying; hasElytra = b.hasElytra; frostWalker = b.frostWalker; dead = b.dead; sleeping = b.sleeping;
        nearWall = b.nearWall; pushableNearby = b.pushableNearby;
        speedAmplifier = b.speedAmplifier; jumpAmplifier = b.jumpAmplifier; x = b.x; y = b.y; z = b.z; yaw = b.yaw; pitch = b.pitch; tick = b.tick;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int gamemode; private String dimension = ""; private int pingMs; private double tps = 20.0;
        private boolean inVehicle, gliding, riptiding, inWater, inLava, onIce, onLadder, inWeb, onSlime, onSoulSand,
                blockBelowSolid, blockAboveSolid, blockBelowLiquid, levitation, slowFalling, flyingAllowed, isFlying, hasElytra, frostWalker, dead, sleeping,
                nearWall, pushableNearby;
        private int speedAmplifier, jumpAmplifier; private double x, y, z; private float yaw, pitch; private long tick;

        public Builder gamemode(int v) { gamemode = v; return this; }
        public Builder dimension(String v) { dimension = v == null ? "" : v; return this; }
        public Builder pingMs(int v) { pingMs = v; return this; }
        public Builder tps(double v) { tps = v; return this; }
        public Builder inVehicle(boolean v) { inVehicle = v; return this; }
        public Builder gliding(boolean v) { gliding = v; return this; }
        public Builder riptiding(boolean v) { riptiding = v; return this; }
        public Builder inWater(boolean v) { inWater = v; return this; }
        public Builder inLava(boolean v) { inLava = v; return this; }
        public Builder onIce(boolean v) { onIce = v; return this; }
        public Builder onLadder(boolean v) { onLadder = v; return this; }
        public Builder inWeb(boolean v) { inWeb = v; return this; }
        public Builder onSlime(boolean v) { onSlime = v; return this; }
        public Builder onSoulSand(boolean v) { onSoulSand = v; return this; }
        public Builder blockBelowSolid(boolean v) { blockBelowSolid = v; return this; }
        public Builder blockAboveSolid(boolean v) { blockAboveSolid = v; return this; }
        public Builder blockBelowLiquid(boolean v) { blockBelowLiquid = v; return this; }
        public Builder levitation(boolean v) { levitation = v; return this; }
        public Builder slowFalling(boolean v) { slowFalling = v; return this; }
        public Builder flyingAllowed(boolean v) { flyingAllowed = v; return this; }
        public Builder isFlying(boolean v) { isFlying = v; return this; }
        public Builder hasElytra(boolean v) { hasElytra = v; return this; }
        public Builder frostWalker(boolean v) { frostWalker = v; return this; }
        public Builder dead(boolean v) { dead = v; return this; }
        public Builder sleeping(boolean v) { sleeping = v; return this; }
        public Builder nearWall(boolean v) { nearWall = v; return this; }
        public Builder pushableNearby(boolean v) { pushableNearby = v; return this; }
        public Builder speedAmplifier(int v) { speedAmplifier = v; return this; }
        public Builder jumpAmplifier(int v) { jumpAmplifier = v; return this; }
        public Builder x(double v) { x = v; return this; }
        public Builder y(double v) { y = v; return this; }
        public Builder z(double v) { z = v; return this; }
        public Builder yaw(float v) { yaw = v; return this; }
        public Builder pitch(float v) { pitch = v; return this; }
        public Builder tick(long v) { tick = v; return this; }
        public PlayerContext build() { return new PlayerContext(this); }
    }
}
