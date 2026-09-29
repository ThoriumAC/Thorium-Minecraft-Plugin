package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.proto.*;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.entity.Player;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.*;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OutboundCapture extends PacketListenerAbstract implements Telemetry.OutboundCaptureHook {
    private final Telemetry telemetry;
    private final EntityTracker entities;
    private final ErrorGate gate;
    private final Map<UUID, MoveCoalescer> moves = new ConcurrentHashMap<>();
    static final Map<UUID, double[]> PULLS = new ConcurrentHashMap<>();
    private static final Set<PacketType.Play.Server> HANDLED = handled();
    private static final Map<PacketType.Play.Server, String> SITES = new EnumMap<>(PacketType.Play.Server.class);
    static { for (PacketType.Play.Server t : PacketType.Play.Server.values()) SITES.put(t, "out:" + t.name()); }

    private static Set<PacketType.Play.Server> handled() {
        return EnumSet.of(
                SPAWN_ENTITY, SPAWN_LIVING_ENTITY, SPAWN_PLAYER, ENTITY_RELATIVE_MOVE,
                ENTITY_RELATIVE_MOVE_AND_ROTATION, ENTITY_TELEPORT, ENTITY_POSITION_SYNC,
                ENTITY_VELOCITY, DESTROY_ENTITIES, ENTITY_METADATA, UPDATE_ATTRIBUTES, ENTITY_EFFECT,
                REMOVE_ENTITY_EFFECT, ENTITY_EQUIPMENT, SET_PASSENGERS, PLAYER_POSITION_AND_LOOK,
                PLAYER_ABILITIES, CHANGE_GAME_STATE, RESPAWN, SET_SLOT, WINDOW_ITEMS, OPEN_WINDOW,
                OPEN_HORSE_WINDOW, CLOSE_WINDOW, HELD_ITEM_CHANGE, SET_COOLDOWN, EXPLOSION,
                ENTITY_STATUS, UPDATE_HEALTH, KEEP_ALIVE, BLOCK_CHANGE, MULTI_BLOCK_CHANGE,
                INITIALIZE_WORLD_BORDER, WORLD_BORDER_SIZE, WORLD_BORDER_CENTER,
                WORLD_BORDER_LERP_SIZE);
    }

    public OutboundCapture(Telemetry telemetry, EntityTracker entities, ErrorGate gate) {
        super(PacketListenerPriority.MONITOR);
        this.telemetry = telemetry; this.entities = entities; this.gate = gate;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!(type instanceof PacketType.Play.Server) || !HANDLED.contains(type)) return;
        Object po = event.getPlayer();
        if (!(po instanceof Player)) return;
        Player p = (Player) po;
        PacketType.Play.Server t = (PacketType.Play.Server) type;
        gate.run(SITES.get(t), () -> dispatch(t, event, p, event.getUser()));
    }

    @Override
    public void flushMoves(Player p, long tick) {
        flushEntered(p);
        MoveCoalescer c = moves.get(p.getUniqueId());
        if (c == null) return;
        c.drain(m -> telemetry.outbound(p, Outbound.newBuilder().setEntityMove(m)));
    }

    private void flushEntered(Player p) {
        entities.entered(p.getUniqueId(), k -> {
            telemetry.outbound(p, Outbound.newBuilder().setEntityMove(EntityMove.newBuilder().setId(k.id)
                    .setPosition(Vec3.newBuilder().setX(k.x).setY(k.y).setZ(k.z))
                    .setOnGround(k.onGround).setHasLook(true).setYaw(k.yaw).setPitch(k.pitch)));
            EntityMetadata f = k.flags;
            if (f != null) telemetry.outbound(p, Outbound.newBuilder().setEntityMetadata(f));
        });
    }

    @Override
    public void restate(Player p) {
        long[] last = {-1};
        entities.restate(p.getUniqueId(), k -> last[0] = telemetry.outbound(p, Outbound.newBuilder().setEntitySpawn(EntitySpawn.newBuilder()
                .setId(k.id).setType(k.type).setPosition(Vec3.newBuilder().setX(k.x).setY(k.y).setZ(k.z)).setYaw(k.yaw).setPitch(k.pitch))));
        if (last[0] >= 0) telemetry.fence(p, last[0]);
    }

    @Override
    public void forget(UUID id) { moves.remove(id); entities.forget(id); PULLS.remove(id); }

    private void fenced(PacketSendEvent event, Player p, Outbound.Builder o) {
        long s = telemetry.outbound(p, o);
        event.getPostTasks().add(() -> gate.run("out:fence", () -> telemetry.fence(p, s)));
    }

    private void dispatch(PacketType.Play.Server t, PacketSendEvent event, Player p, User user) {
        UUID viewer = p.getUniqueId();
        int self = user == null ? -1 : user.getEntityId();
        switch (t) {
            case SPAWN_ENTITY: case SPAWN_LIVING_ENTITY: case SPAWN_PLAYER: {
                WrapperPlayServerSpawnEntity w = new WrapperPlayServerSpawnEntity(event);
                Vector3d pos = w.getPosition();
                EntitySpawn.Builder b = EntitySpawn.newBuilder().setId(w.getEntityId())
                        .setType(w.getEntityType() == null ? "" : String.valueOf(w.getEntityType().getName()))
                        .setPosition(vec(pos)).setYaw(w.getYaw()).setPitch(w.getPitch()).setHeadYaw(w.getHeadYaw());
                w.getUUID().ifPresent(u -> b.setUuid(ac.thorium.mc.plugin.telemetry.Names.bytes(u)));
                w.getVelocity().ifPresent(v -> b.setVelocity(vec(v)));
                com.github.retrooper.packetevents.protocol.entity.type.EntityType et = w.getEntityType();
                String key = et == null ? "" : et.getName().getKey();
                boolean wanted = et != null && EntityTracker.wanted(key, et.isInstanceOf(EntityTypes.LIVINGENTITY) || et.isInstanceOf(EntityTypes.MINECART_ABSTRACT));
                entities.spawn(viewer, w.getEntityId(), b.getType(), wanted, pos.getX(), pos.getY(), pos.getZ(), w.getYaw(), w.getPitch());
                fenced(event, p, Outbound.newBuilder().setEntitySpawn(b));
                break;
            }
            case ENTITY_RELATIVE_MOVE: case ENTITY_RELATIVE_MOVE_AND_ROTATION: case ENTITY_TELEPORT: case ENTITY_POSITION_SYNC: {
                int id; double x, y, z; boolean look, ground; float yaw = 0, pitch = 0;
                EntityTracker.Known k;
                if (t == PacketType.Play.Server.ENTITY_TELEPORT) {
                    WrapperPlayServerEntityTeleport w = new WrapperPlayServerEntityTeleport(event);
                    Vector3d v = w.getPosition();
                    id = w.getEntityId(); x = v.getX(); y = v.getY(); z = v.getZ(); look = true; yaw = w.getYaw(); pitch = w.getPitch(); ground = w.isOnGround();
                    if ((k = entities.get(viewer, id)) == null) return;
                } else if (t == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                    WrapperPlayServerEntityPositionSync w = new WrapperPlayServerEntityPositionSync(event);
                    Vector3d v = w.getValues().getPosition();
                    id = w.getId(); x = v.getX(); y = v.getY(); z = v.getZ(); look = true; yaw = w.getValues().getYaw(); pitch = w.getValues().getPitch(); ground = w.isOnGround();
                    if ((k = entities.get(viewer, id)) == null) return;
                } else if (t == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                    WrapperPlayServerEntityRelativeMoveAndRotation w = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
                    id = w.getEntityId();
                    if ((k = entities.get(viewer, id)) == null) return;
                    x = k.x + w.getDeltaX(); y = k.y + w.getDeltaY(); z = k.z + w.getDeltaZ();
                    look = true; yaw = w.getYaw(); pitch = w.getPitch(); ground = w.isOnGround();
                } else {
                    WrapperPlayServerEntityRelativeMove w = new WrapperPlayServerEntityRelativeMove(event);
                    id = w.getEntityId();
                    if ((k = entities.get(viewer, id)) == null) return;
                    x = k.x + w.getDeltaX(); y = k.y + w.getDeltaY(); z = k.z + w.getDeltaZ();
                    ground = w.isOnGround(); look = false;
                }
                k.moveTo(x, y, z); k.onGround = ground;
                if (look) { k.yaw = yaw; k.pitch = pitch; }
                if (!entities.contains(viewer, id)) return;
                moves.computeIfAbsent(viewer, u -> new MoveCoalescer()).offer(id, x, y, z, look, yaw, pitch, ground);
                break;
            }
            case ENTITY_VELOCITY: {
                WrapperPlayServerEntityVelocity w = new WrapperPlayServerEntityVelocity(event);
                if (w.getEntityId() != self && !entities.contains(viewer, w.getEntityId())) return;
                fenced(event, p, Outbound.newBuilder().setEntityVelocity(EntityVelocityOut.newBuilder().setId(w.getEntityId()).setVelocity(vec(w.getVelocity()))));
                break;
            }
            case DESTROY_ENTITIES: {
                EntityDestroy.Builder b = EntityDestroy.newBuilder();
                int[] ids = new WrapperPlayServerDestroyEntities(event).getEntityIds();
                for (int id : ids) b.addIds(id);
                entities.destroy(viewer, ids);
                if (b.getIdsCount() > 0) fenced(event, p, Outbound.newBuilder().setEntityDestroy(b));
                break;
            }
            case ENTITY_METADATA: {
                WrapperPlayServerEntityMetadata w = new WrapperPlayServerEntityMetadata(event);
                int id = w.getEntityId();
                EntityTracker.Known k = id == self ? null : entities.get(viewer, id);
                if (id != self && (k == null || !k.wanted)) return;
                EntityMetadata.Builder b = MetadataDecoder.decode(id, w.getEntityMetadata());
                if (b == null) return;
                if (k != null) k.flags = mergeMetadata(k.flags, b, id);
                if (id == self || entities.contains(viewer, id)) fenced(event, p, Outbound.newBuilder().setEntityMetadata(b));
                break;
            }
            case UPDATE_ATTRIBUTES: {
                WrapperPlayServerUpdateAttributes w = new WrapperPlayServerUpdateAttributes(event);
                if (w.getEntityId() != self && !entities.contains(viewer, w.getEntityId())) return;
                EntityAttributes.Builder b = EntityAttributes.newBuilder().setId(w.getEntityId());
                for (WrapperPlayServerUpdateAttributes.Property pr : w.getProperties()) {
                    Attribute.Builder a = Attribute.newBuilder().setName(pr.getKey() == null ? "" : pr.getKey()).setBase(pr.getValue());
                    for (WrapperPlayServerUpdateAttributes.PropertyModifier m : pr.getModifiers()) {
                        a.addModifiers(AttributeModifier.newBuilder().setAmount(m.getAmount()).setOp(m.getOperation() == null ? 0 : m.getOperation().ordinal()));
                    }
                    b.addAttributes(a);
                }
                fenced(event, p, Outbound.newBuilder().setEntityAttributes(b));
                break;
            }
            case ENTITY_EFFECT: {
                WrapperPlayServerEntityEffect w = new WrapperPlayServerEntityEffect(event);
                if (w.getEntityId() != self) return;
                fenced(event, p, Outbound.newBuilder().setEntityEffect(EntityEffect.newBuilder().setId(self)
                        .setEffect(effectName(w.getPotionType())).setAmplifier(w.getEffectAmplifier()).setDuration(w.getEffectDurationTicks())));
                break;
            }
            case REMOVE_ENTITY_EFFECT: {
                WrapperPlayServerRemoveEntityEffect w = new WrapperPlayServerRemoveEntityEffect(event);
                if (w.getEntityId() != self) return;
                fenced(event, p, Outbound.newBuilder().setEntityEffect(EntityEffect.newBuilder().setId(self).setEffect(effectName(w.getPotionType())).setRemove(true)));
                break;
            }
            case ENTITY_EQUIPMENT: {
                WrapperPlayServerEntityEquipment w = new WrapperPlayServerEntityEquipment(event);
                if (w.getEntityId() != self && !entities.contains(viewer, w.getEntityId())) return;
                EntityEquipment.Builder b = EntityEquipment.newBuilder().setId(w.getEntityId());
                for (com.github.retrooper.packetevents.protocol.player.Equipment e : w.getEquipment()) b.addSlots(ItemNames.slot(e));
                fenced(event, p, Outbound.newBuilder().setEntityEquipment(b));
                break;
            }
            case SET_PASSENGERS: {
                WrapperPlayServerSetPassengers w = new WrapperPlayServerSetPassengers(event);
                Passengers.Builder b = Passengers.newBuilder().setVehicle(w.getEntityId());
                boolean mine = w.getEntityId() == self;
                for (int id : w.getPassengers()) { b.addIds(id); mine |= id == self; }
                if (mine) { entities.pin(viewer, w.getEntityId()); for (int id : w.getPassengers()) entities.pin(viewer, id); }
                if (mine || entities.contains(viewer, w.getEntityId())) fenced(event, p, Outbound.newBuilder().setPassengers(b));
                break;
            }
            case PLAYER_POSITION_AND_LOOK: {
                WrapperPlayServerPlayerPositionAndLook w = new WrapperPlayServerPlayerPositionAndLook(event);
                PlayerPosition.Builder b = PlayerPosition.newBuilder().setTeleportId(w.getTeleportId())
                        .setPosition(Vec3.newBuilder().setX(w.getX()).setY(w.getY()).setZ(w.getZ()))
                        .setLook(Look.newBuilder().setYaw(w.getYaw()).setPitch(w.getPitch()))
                        .setRelativeFlags(w.getRelativeMask()).setDismount(w.isDismountVehicle());
                telemetry.outbound(p, Outbound.newBuilder().setPlayerPosition(b));
                final int tid = w.getTeleportId();
                final double tx = w.getX(), ty = w.getY(), tz = w.getZ();
                event.getPostTasks().add(() -> gate.run("out:teleport-tx", () -> telemetry.teleportSent(p, tid, tx, ty, tz)));
                break;
            }
            case PLAYER_ABILITIES: {
                WrapperPlayServerPlayerAbilities w = new WrapperPlayServerPlayerAbilities(event);
                fenced(event, p, Outbound.newBuilder().setPlayerAbilities(PlayerAbilities.newBuilder().setMayFly(w.isFlightAllowed())
                        .setFlying(w.isFlying()).setFlySpeed(w.getFlySpeed()).setWalkSpeed(w.getFOVModifier()).setInvulnerable(w.isInGodMode())));
                break;
            }
            case CHANGE_GAME_STATE: {
                WrapperPlayServerChangeGameState w = new WrapperPlayServerChangeGameState(event);
                fenced(event, p, Outbound.newBuilder().setGameState(GameState.newBuilder().setReason(w.getReason() == null ? 0 : w.getReason().ordinal()).setValue(w.getValue())));
                break;
            }
            case RESPAWN: {
                WrapperPlayServerRespawn w = new WrapperPlayServerRespawn(event);
                fenced(event, p, Outbound.newBuilder().setRespawn(RespawnOut.newBuilder()
                        .setDimension(w.getDimension() == null ? "" : String.valueOf(w.getDimension().getDimensionName()))
                        .setGamemode(w.getGameMode() == null ? 0 : w.getGameMode().ordinal()).setKeepAll(w.isKeepingAllPlayerData())));
                break;
            }
            case SET_SLOT: {
                WrapperPlayServerSetSlot w = new WrapperPlayServerSetSlot(event);
                fenced(event, p, Outbound.newBuilder().setSlot(ItemNames.slotOut(w.getSlot(), w.getItem()).setWindow(w.getWindowId()).setStateId(w.getStateId())));
                break;
            }
            case WINDOW_ITEMS: {
                WrapperPlayServerWindowItems w = new WrapperPlayServerWindowItems(event);
                WindowItems.Builder b = WindowItems.newBuilder().setWindow(w.getWindowId()).setStateId(w.getStateId());
                int i = 0;
                for (com.github.retrooper.packetevents.protocol.item.ItemStack it : w.getItems()) {
                    if (it != null && !it.isEmpty()) b.addSlots(ItemNames.slotOut(i, it));
                    i++;
                }
                fenced(event, p, Outbound.newBuilder().setWindowItems(b));
                break;
            }
            case OPEN_WINDOW: {
                WrapperPlayServerOpenWindow w = new WrapperPlayServerOpenWindow(event);
                fenced(event, p, Outbound.newBuilder().setOpenWindow(OpenWindow.newBuilder().setWindow(w.getContainerId()).setType(String.valueOf(w.getType()))));
                break;
            }
            case OPEN_HORSE_WINDOW: {
                WrapperPlayServerOpenHorseWindow w = new WrapperPlayServerOpenHorseWindow(event);
                fenced(event, p, Outbound.newBuilder().setOpenWindow(OpenWindow.newBuilder().setWindow(w.getWindowId()).setType("minecraft:horse")));
                break;
            }
            case CLOSE_WINDOW:
                fenced(event, p, Outbound.newBuilder().setCloseWindow(CloseWindowOut.newBuilder().setWindow(new WrapperPlayServerCloseWindow(event).getWindowId())));
                break;
            case HELD_ITEM_CHANGE:
                fenced(event, p, Outbound.newBuilder().setHeldSlot(HeldSlotOut.newBuilder().setSlot(new WrapperPlayServerHeldItemChange(event).getSlot())));
                break;
            case SET_COOLDOWN: {
                WrapperPlayServerSetCooldown w = new WrapperPlayServerSetCooldown(event);
                fenced(event, p, Outbound.newBuilder().setCooldown(Cooldown.newBuilder()
                        .setItem(w.getItem() == null ? "" : String.valueOf(w.getItem().getName())).setTicks(w.getCooldownTicks())));
                break;
            }
            case EXPLOSION: {
                WrapperPlayServerExplosion w = new WrapperPlayServerExplosion(event);
                ExplosionOut.Builder b = ExplosionOut.newBuilder().setPosition(vec(w.getPosition())).setStrength(w.getStrength());
                Vec3.Builder m = motion(w);
                if (m != null) b.setMotion(m);
                fenced(event, p, Outbound.newBuilder().setExplosion(b));
                break;
            }
            case ENTITY_STATUS: {
                if (new WrapperPlayServerEntityStatus(event).getStatus() != 31) break;
                double[] v = PULLS.remove(viewer);
                if (v == null) break;
                fenced(event, p, Outbound.newBuilder().setExplosion(ExplosionOut.newBuilder().setPull(true)
                        .setMotion(Vec3.newBuilder().setX(v[0]).setY(v[1]).setZ(v[2]))));
                break;
            }
            case UPDATE_HEALTH: {
                WrapperPlayServerUpdateHealth w = new WrapperPlayServerUpdateHealth(event);
                fenced(event, p, Outbound.newBuilder().setHealth(Health.newBuilder().setHealth(w.getHealth()).setFood(w.getFood()).setSaturation(w.getFoodSaturation())));
                break;
            }
            case KEEP_ALIVE:
                telemetry.outbound(p, Outbound.newBuilder().setKeepAlive(KeepAliveOut.newBuilder().setId(new WrapperPlayServerKeepAlive(event).getId())));
                break;
            case BLOCK_CHANGE: {
                WrapperPlayServerBlockChange w = new WrapperPlayServerBlockChange(event);
                fenced(event, p, Outbound.newBuilder().setBlockChange(BlockChangeOut.newBuilder()
                        .setX(w.getBlockPosition().getX()).setY(w.getBlockPosition().getY()).setZ(w.getBlockPosition().getZ())
                        .setState(String.valueOf(w.getBlockState()))));
                telemetry.blockChangeSent(p, w.getBlockPosition().getX(), w.getBlockPosition().getY(), w.getBlockPosition().getZ());
                break;
            }
            case MULTI_BLOCK_CHANGE: {
                if (user == null) break;
                WrapperPlayServerMultiBlockChange w = new WrapperPlayServerMultiBlockChange(event);
                MultiBlockChangeOut.Builder m = MultiBlockChangeOut.newBuilder();
                long lastSection = Long.MIN_VALUE;
                for (WrapperPlayServerMultiBlockChange.EncodedBlock b : w.getBlocks()) {
                    long section = ((long) (b.getX() >> 4) << 42) ^ ((long) (b.getY() >> 4) << 21) ^ (b.getZ() >> 4);
                    if (section != lastSection) { telemetry.blockChangeSent(p, b.getX(), b.getY(), b.getZ()); lastSection = section; }
                    if (!entities.nearViewer(viewer, b.getX(), b.getY(), b.getZ(), 16)) continue;
                    m.addBlocks(BlockChangeOut.newBuilder().setX(b.getX()).setY(b.getY()).setZ(b.getZ())
                            .setState(String.valueOf(b.getBlockState(user.getClientVersion()))));
                }
                if (m.getBlocksCount() > 0) fenced(event, p, Outbound.newBuilder().setMultiBlockChange(m));
                break;
            }
            case INITIALIZE_WORLD_BORDER: case WORLD_BORDER_SIZE: case WORLD_BORDER_CENTER: case WORLD_BORDER_LERP_SIZE:
                fenced(event, p, Outbound.newBuilder().setWorldBorder(WorldBorderDecoder.decode(t, event)));
                break;
            default:
                break;
        }
    }

    private static Vec3.Builder motion(WrapperPlayServerExplosion w) {
        try {
            Vector3d k = w.getKnockback();
            if (k != null && (k.getX() != 0 || k.getY() != 0 || k.getZ() != 0)) return Vec3.newBuilder().setX(k.getX()).setY(k.getY()).setZ(k.getZ());
        } catch (Throwable ignored) {}
        try {
            Vector3f m = w.getPlayerMotion();
            if (m != null && (m.getX() != 0 || m.getY() != 0 || m.getZ() != 0)) return Vec3.newBuilder().setX(m.getX()).setY(m.getY()).setZ(m.getZ());
        } catch (Throwable ignored) {}
        return null;
    }

    private static String effectName(Object potionType) {
        if (potionType == null) return "";
        try {
            java.lang.reflect.Method m = potionType.getClass().getMethod("getName");
            return String.valueOf(m.invoke(potionType));
        } catch (Throwable t) { return String.valueOf(potionType); }
    }

    private static Vec3 vec(Vector3d v) { return Vec3.newBuilder().setX(v.getX()).setY(v.getY()).setZ(v.getZ()).build(); }

    /** Keeps every part {@code b} carries on top of what was already cached, so a part received while the entity is out of range is not lost. */
    private static EntityMetadata mergeMetadata(EntityMetadata prev, EntityMetadata.Builder b, int id) {
        EntityMetadata.Builder m = prev != null ? prev.toBuilder() : EntityMetadata.newBuilder().setId(id);
        if (b.getHasFlags()) m.setHasFlags(true).setSneaking(b.getSneaking()).setSprinting(b.getSprinting())
                .setSwimming(b.getSwimming()).setInvisible(b.getInvisible()).setGliding(b.getGliding());
        if (b.getHasPose()) m.setHasPose(true).setPose(b.getPose()).setRiptiding(b.getRiptiding());
        if (b.getHasSize()) m.setHasSize(true).setSize(b.getSize());
        if (b.getHasBaby()) m.setHasBaby(true).setBaby(b.getBaby());
        if (b.getHasPeek()) m.setHasPeek(true).setPeek(b.getPeek());
        return m.build();
    }
}
