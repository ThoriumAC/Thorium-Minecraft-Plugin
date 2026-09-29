package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SampleFactory {
    private SampleFactory() {}

    private static Vec3 vec(double x, double y, double z) { return Vec3.newBuilder().setX(x).setY(y).setZ(z).build(); }
    private static Look look(float yaw, float pitch) { return Look.newBuilder().setYaw(yaw).setPitch(pitch).build(); }

    public static Sample.Builder rotation(float yaw, float pitch, boolean onGround) {
        return Sample.newBuilder().setRotation(RotationSample.newBuilder().setLook(look(yaw, pitch)).setOnGround(onGround));
    }

    public static Sample.Builder client(String brand, List<String> channels, int protocolVersion) {
        ClientSample.Builder c = ClientSample.newBuilder().setBrand(brand == null ? "" : brand).setProtocolVersion(protocolVersion);
        if (channels != null) c.addAllChannels(channels);
        return Sample.newBuilder().setClient(c);
    }

    public static Sample.Builder payload(int length) {
        return Sample.newBuilder().setClient(ClientSample.newBuilder().setLength(length));
    }

    public static Sample.Builder transaction(int id, TransactionPhase phase, TransactionCause cause, long rttMs) {
        return transaction(id, phase, cause, rttMs, 0L);
    }

    public static Sample.Builder transaction(int id, TransactionPhase phase, TransactionCause cause, long rttMs, long fencesSeq) {
        return Sample.newBuilder().setTransaction(TransactionSample.newBuilder().setId(id).setPhase(phase).setCause(cause)
                .setRttMs(Math.max(0, rttMs)).setFencesSeq((int) fencesSeq));
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

    public static Sample.Builder movement(double x, double y, double z, float yaw, float pitch, boolean hasPos, boolean hasLook,
                                            boolean onGround, boolean horizontalCollision, int teleportId, boolean cancelled) {
        MovementSample.Builder m = MovementSample.newBuilder().setHasPosition(hasPos).setHasLook(hasLook).setOnGround(onGround)
                .setHorizontalCollision(horizontalCollision).setTeleportId(teleportId).setCancelled(cancelled);
        if (hasPos) m.setPosition(vec(x, y, z));
        if (hasLook) m.setLook(look(yaw, pitch));
        return Sample.newBuilder().setMovement(m);
    }

    public static Sample.Builder input(boolean fwd, boolean back, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
        return Sample.newBuilder().setInput(InputSample.newBuilder().setForward(fwd).setBackward(back).setLeft(left).setRight(right).setJump(jump).setShift(shift).setSprint(sprint));
    }

    public static Sample.Builder legacyInput(float forward, float sideways, boolean jump, boolean unmount) {
        return Sample.newBuilder().setInput(InputSample.newBuilder().setLegacyForward(forward).setLegacySideways(sideways).setJump(jump).setLegacyUnmount(unmount));
    }

    public static Sample.Builder tickEnd() { return Sample.newBuilder().setTickEnd(TickEndSample.getDefaultInstance()); }

    public static Sample.Builder entityAction(EntityActionKind k, int jumpBoost) {
        return Sample.newBuilder().setEntityAction(EntityActionSample.newBuilder().setAction(k).setJumpBoost(jumpBoost));
    }

    public static Sample.Builder abilities(boolean flying) { return Sample.newBuilder().setAbilities(AbilitiesSample.newBuilder().setFlying(flying)); }

    public static Sample.Builder keepAlive(long id) { return Sample.newBuilder().setKeepAlive(KeepAliveSample.newBuilder().setId(id)); }

    public static Sample.Builder useItem(int hand, int seq, boolean hasLook, float yaw, float pitch) {
        UseItemSample.Builder b = UseItemSample.newBuilder().setHand(hand).setSequence(seq).setHasLook(hasLook);
        if (hasLook) b.setLook(look(yaw, pitch));
        return Sample.newBuilder().setUseItem(b);
    }

    public static Sample.Builder heldSlot(int slot) { return Sample.newBuilder().setHeldSlot(HeldSlotSample.newBuilder().setSlot(slot)); }

    public static Sample.Builder vehicleMove(double x, double y, double z, float yaw, float pitch, boolean hasOnGround, boolean onGround) {
        return Sample.newBuilder().setVehicleMove(VehicleMoveSample.newBuilder().setPosition(vec(x, y, z)).setLook(look(yaw, pitch)).setHasOnGround(hasOnGround).setOnGround(onGround));
    }

    public static Sample.Builder paddle(boolean left, boolean right) { return Sample.newBuilder().setPaddle(PaddleSample.newBuilder().setLeft(left).setRight(right)); }

    public static Sample.Builder clientStatus(int action) { return Sample.newBuilder().setClientStatus(ClientStatusSample.newBuilder().setAction(action)); }

    public static Sample.Builder settings(int viewDistance, int mainHand, int skinParts) {
        return Sample.newBuilder().setSettings(SettingsSample.newBuilder().setViewDistance(viewDistance).setMainHand(mainHand).setSkinParts(skinParts));
    }

    public static Sample.Builder spectate(UUID target) {
        return Sample.newBuilder().setSpectate(SpectateSample.newBuilder().setTarget(ac.thorium.mc.plugin.telemetry.Names.bytes(target)));
    }

    public static Sample.Builder text(TextKind kind, int length, int pages, boolean signed) {
        return Sample.newBuilder().setText(TextSample.newBuilder().setKind(kind).setLength(length).setPages(pages).setSigned(signed));
    }

    public static Sample.Builder other(int packetId, String name) { return Sample.newBuilder().setOther(OtherSample.newBuilder().setPacketId(packetId).setName(name == null ? "" : name)); }

    public static Sample.Builder combat(CombatInteract interact, int targetEntityId, PlayerRef target,
                                          double ax, double ay, double az, float yaw, float pitch,
                                          double cx, double cy, double cz, int hand, boolean hasCursor, boolean sneaking) {
        CombatSample.Builder b = CombatSample.newBuilder()
                .setAction(interact == CombatInteract.COMBAT_INTERACT_ATTACK ? CombatAction.COMBAT_ACTION_ATTACK : CombatAction.COMBAT_ACTION_INTERACT)
                .setInteract(interact).setTargetEntityId(targetEntityId)
                .setAttackerPosition(vec(ax, ay, az)).setAttackerLook(look(yaw, pitch)).setHand(hand).setSneaking(sneaking);
        if (target != null) { b.setTarget(target); b.setTargetIsPlayer(true); }
        if (hasCursor) b.setCursor(vec(cx, cy, cz));
        return Sample.newBuilder().setCombat(b);
    }

    public static Sample.Builder swing(int hand) {
        return Sample.newBuilder().setCombat(CombatSample.newBuilder().setAction(CombatAction.COMBAT_ACTION_SWING).setHand(hand));
    }

    public static Sample.Builder block(BlockAction action, int x, int y, int z, int face, float yaw, float pitch,
                                         double px, double py, double pz, int sequence, int hand,
                                         double cx, double cy, double cz, boolean insideBlock, boolean borderHit) {
        BlockSample.Builder b = BlockSample.newBuilder().setAction(action).setX(x).setY(y).setZ(z).setFace(face)
                .setLook(look(yaw, pitch)).setPlayerPosition(vec(px, py, pz)).setSequence(sequence).setHand(hand)
                .setInsideBlock(insideBlock).setWorldBorderHit(borderHit);
        if (action == BlockAction.BLOCK_ACTION_PLACE) b.setCursor(vec(cx, cy, cz));
        return Sample.newBuilder().setBlock(b);
    }

    public static Sample.Builder inventory(int windowId, int slot, int button, String action, int clickType, int stateId, int changedSlots, boolean hasCarried) {
        return Sample.newBuilder().setInventory(InventorySample.newBuilder().setWindowId(windowId).setSlot(slot).setButton(button).setAction(action == null ? "" : action)
                .setClickType(clickType).setStateId(stateId).setChangedSlots(changedSlots).setHasCarried(hasCarried));
    }

    public static BlockAction diggingAction(String name) {
        if (name == null) return BlockAction.BLOCK_ACTION_UNSPECIFIED;
        switch (name) {
            case "START_DIGGING": return BlockAction.BLOCK_ACTION_DIG_START;
            case "CANCELLED_DIGGING": return BlockAction.BLOCK_ACTION_DIG_ABORT;
            case "FINISHED_DIGGING": return BlockAction.BLOCK_ACTION_DIG_FINISH;
            case "DROP_ITEM": return BlockAction.BLOCK_ACTION_DROP_ITEM;
            case "DROP_ITEM_STACK": return BlockAction.BLOCK_ACTION_DROP_STACK;
            case "RELEASE_USE_ITEM": return BlockAction.BLOCK_ACTION_RELEASE_USE_ITEM;
            case "SWAP_ITEM_WITH_OFFHAND": return BlockAction.BLOCK_ACTION_SWAP_HANDS;
            default: return BlockAction.BLOCK_ACTION_UNSPECIFIED;
        }
    }
}
