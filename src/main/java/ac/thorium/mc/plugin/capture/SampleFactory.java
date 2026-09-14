package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SampleFactory {
    private SampleFactory() {}

    private static Vec3 vec(double x, double y, double z) { return Vec3.newBuilder().setX(x).setY(y).setZ(z).build(); }
    private static Look look(float yaw, float pitch) { return Look.newBuilder().setYaw(yaw).setPitch(pitch).build(); }

    public static Sample.Builder movement(double x, double y, double z, float yaw, float pitch, boolean hasPos, boolean hasLook, boolean onGround, boolean sprinting, boolean sneaking) {
        return movement(x, y, z, yaw, pitch, hasPos, hasLook, onGround, sprinting, sneaking, false);
    }

    public static Sample.Builder movement(double x, double y, double z, float yaw, float pitch, boolean hasPos, boolean hasLook, boolean onGround, boolean sprinting, boolean sneaking, boolean horizontalCollision) {
        MovementSample.Builder m = MovementSample.newBuilder().setHasPosition(hasPos).setHasLook(hasLook).setOnGround(onGround).setSprinting(sprinting).setSneaking(sneaking).setHorizontalCollision(horizontalCollision);
        if (hasPos) m.setPosition(vec(x, y, z));
        if (hasLook) m.setLook(look(yaw, pitch));
        return Sample.newBuilder().setMovement(m);
    }

    public static Sample.Builder rotation(float yaw, float pitch, boolean onGround) {
        return Sample.newBuilder().setRotation(RotationSample.newBuilder().setLook(look(yaw, pitch)).setOnGround(onGround));
    }

    public static Sample.Builder combat(CombatAction action, int targetEntityId, PlayerRef target, double ax, double ay, double az, float yaw, float pitch,
                                        double tx, double ty, double tz, float width, float height, boolean targetIsPlayer) {
        CombatSample.Builder c = CombatSample.newBuilder().setAction(action).setTargetEntityId(targetEntityId)
                .setAttackerPosition(vec(ax, ay, az)).setAttackerLook(look(yaw, pitch))
                .setTargetPosition(vec(tx, ty, tz)).setTargetWidth(width).setTargetHeight(height).setTargetIsPlayer(targetIsPlayer);
        if (target != null) c.setTarget(target);
        return Sample.newBuilder().setCombat(c);
    }

    public static Sample.Builder block(BlockAction action, int x, int y, int z, int face, String blockId, float yaw, float pitch, double px, double py, double pz,
                                       int expectedBreakTicks, boolean sneaking, boolean sprinting) {
        return Sample.newBuilder().setBlock(BlockSample.newBuilder().setAction(action).setX(x).setY(y).setZ(z).setFace(face).setBlockId(blockId == null ? "" : blockId)
                .setLook(look(yaw, pitch)).setPlayerPosition(vec(px, py, pz)).setExpectedBreakTicks(expectedBreakTicks).setSneaking(sneaking).setSprinting(sprinting));
    }

    public static Sample.Builder inventory(int windowId, int slot, int button, String action) {
        return Sample.newBuilder().setInventory(InventorySample.newBuilder().setWindowId(windowId).setSlot(slot).setButton(button).setAction(action == null ? "" : action));
    }

    public static Sample.Builder client(String brand, List<String> channels, int protocolVersion) {
        ClientSample.Builder c = ClientSample.newBuilder().setBrand(brand == null ? "" : brand).setProtocolVersion(protocolVersion);
        if (channels != null) c.addAllChannels(channels);
        return Sample.newBuilder().setClient(c);
    }

    public static Sample.Builder transaction(int id, TransactionPhase phase, TransactionCause cause, long rttMs) {
        return Sample.newBuilder().setTransaction(TransactionSample.newBuilder().setId(id).setPhase(phase).setCause(cause).setRttMs(Math.max(0, rttMs)));
    }

    public static BlockAction diggingAction(String name) {
        if (name == null) return null;
        switch (name) {
            case "START_DIGGING": return BlockAction.BLOCK_ACTION_DIG_START;
            case "CANCELLED_DIGGING": return BlockAction.BLOCK_ACTION_DIG_ABORT;
            case "FINISHED_DIGGING": return BlockAction.BLOCK_ACTION_DIG_FINISH;
            default: return null;
        }
    }

    public static String windowAction(String name) {
        if (name == null) return "";
        switch (name) {
            case "PICKUP": case "QUICK_MOVE": case "PICKUP_ALL": case "QUICK_CRAFT": case "CLONE": return "CLICK";
            case "THROW": return "DROP";
            case "SWAP": return "SWAP";
            default: return name;
        }
    }

    public static boolean isBrandChannel(String ch) { return "minecraft:brand".equals(ch) || "MC|Brand".equals(ch); }
    public static boolean isRegisterChannel(String ch) { return "minecraft:register".equals(ch) || "REGISTER".equals(ch); }

    /** Brand payloads are a varint length + UTF-8; some clients omit the prefix. */
    public static String decodeBrand(byte[] p) {
        if (p == null || p.length == 0) return "";
        int len = 0, shift = 0, i = 0;
        boolean ok = false;
        while (i < p.length && i < 5) {
            int b = p[i++] & 0xFF;
            len |= (b & 0x7F) << shift; shift += 7;
            if ((b & 0x80) == 0) { ok = true; break; }
        }
        String s = (ok && len >= 0 && i + len == p.length) ? new String(p, i, len, StandardCharsets.UTF_8) : new String(p, StandardCharsets.UTF_8);
        s = s.replace("\0", "").trim();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    public static List<String> decodeRegister(byte[] p) {
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        for (String s : new String(p, StandardCharsets.UTF_8).split("\0")) {
            if (!s.isEmpty() && out.size() < 32) out.add(s.length() > 64 ? s.substring(0, 64) : s);
        }
        return out;
    }
}
