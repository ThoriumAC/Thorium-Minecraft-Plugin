package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.proto.BlockAction;
import ac.thorium.mc.proto.CombatInteract;
import ac.thorium.mc.proto.EntityActionKind;
import ac.thorium.mc.proto.TextKind;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketCapture extends PacketListenerAbstract {
    private static final int MAX_PAYLOAD = 32767;

    private static final class Flags { volatile boolean hasPos; volatile double x, y, z; volatile float yaw, pitch; }

    private static final Map<PacketType.Play.Client, String> SITES = new java.util.EnumMap<>(PacketType.Play.Client.class);
    static { for (PacketType.Play.Client t : PacketType.Play.Client.values()) SITES.put(t, "capture:" + t.name()); }

    private final Telemetry telemetry;
    private final EntityTracker entities;
    private final ErrorGate gate;
    private final Map<UUID, Flags> flags = new ConcurrentHashMap<>();

    public PacketCapture(Telemetry telemetry, ErrorGate gate, EntityTracker entities) {
        super(PacketListenerPriority.MONITOR);
        this.telemetry = telemetry; this.gate = gate; this.entities = entities;
    }

    public void forget(UUID uuid) { flags.remove(uuid); }

    private Flags flags(Player p) { return flags.computeIfAbsent(p.getUniqueId(), k -> new Flags()); }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!(type instanceof PacketType.Play.Client)) return;
        Object po = event.getPlayer();
        if (!(po instanceof Player)) return;
        Player p = (Player) po;
        PacketType.Play.Client t = (PacketType.Play.Client) type;
        gate.run(SITES.get(t), () -> dispatch(t, event, p));
    }

    private void dispatch(PacketType.Play.Client t, PacketReceiveEvent event, Player p) {
        Flags f = flags(p);
        switch (t) {
            case PLAYER_FLYING: case PLAYER_POSITION: case PLAYER_POSITION_AND_ROTATION: case PLAYER_ROTATION:
                onFlying(new WrapperPlayClientPlayerFlying(event), p, event.isCancelled());
                break;
            case VEHICLE_MOVE: {
                WrapperPlayClientVehicleMove w = new WrapperPlayClientVehicleMove(event);
                Vector3d v = w.getPosition();
                f.x = v.getX(); f.y = v.getY(); f.z = v.getZ(); f.yaw = w.getYaw(); f.pitch = w.getPitch(); f.hasPos = true;
                entities.viewerAt(p.getUniqueId(), f.x, f.y, f.z);
                boolean hasGround = event.getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_21_2);
                telemetry.sample(p, SampleFactory.vehicleMove(v.getX(), v.getY(), v.getZ(), w.getYaw(), w.getPitch(), hasGround, hasGround && onGround(w)));
                break;
            }
            case STEER_BOAT: {
                WrapperPlayClientSteerBoat w = new WrapperPlayClientSteerBoat(event);
                telemetry.sample(p, SampleFactory.paddle(w.isLeftPaddleTurning(), w.isRightPaddleTurning()));
                break;
            }
            case STEER_VEHICLE: {
                WrapperPlayClientSteerVehicle w = new WrapperPlayClientSteerVehicle(event);
                telemetry.sample(p, SampleFactory.legacyInput(w.getForward(), w.getSideways(), w.isJump(), w.isUnmount()));
                break;
            }
            case PLAYER_INPUT: {
                WrapperPlayClientPlayerInput w = new WrapperPlayClientPlayerInput(event);
                telemetry.sample(p, SampleFactory.input(w.isForward(), w.isBackward(), w.isLeft(), w.isRight(), w.isJump(), w.isShift(), w.isSprint()));
                break;
            }
            case CLIENT_TICK_END:
                telemetry.sample(p, SampleFactory.tickEnd());
                break;
            case ENTITY_ACTION: {
                WrapperPlayClientEntityAction w = new WrapperPlayClientEntityAction(event);
                telemetry.sample(p, SampleFactory.entityAction(entityActionKind(w.getAction()), w.getJumpBoost()));
                break;
            }
            case PLAYER_ABILITIES:
                telemetry.sample(p, SampleFactory.abilities(new WrapperPlayClientPlayerAbilities(event).isFlying()));
                break;
            case KEEP_ALIVE:
                telemetry.sample(p, SampleFactory.keepAlive(new WrapperPlayClientKeepAlive(event).getId()));
                break;
            case USE_ITEM: {
                WrapperPlayClientUseItem w = new WrapperPlayClientUseItem(event);
                boolean hasLook = event.getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_21);
                telemetry.sample(p, SampleFactory.useItem(w.getHand().ordinal(), w.getSequence(), hasLook, hasLook ? w.getYaw() : 0f, hasLook ? w.getPitch() : 0f));
                break;
            }
            case HELD_ITEM_CHANGE:
                telemetry.sample(p, SampleFactory.heldSlot(new WrapperPlayClientHeldItemChange(event).getSlot()));
                break;
            case INTERACT_ENTITY: {
                WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
                CombatInteract ci = w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK ? CombatInteract.COMBAT_INTERACT_ATTACK
                        : w.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT ? CombatInteract.COMBAT_INTERACT_INTERACT_AT : CombatInteract.COMBAT_INTERACT_INTERACT;
                Vector3f c = w.getTarget().orElse(null);
                int hand = w.getHand() == null ? 0 : w.getHand().ordinal();
                telemetry.sample(p, SampleFactory.combat(ci, w.getEntityId(), telemetry.refFor(w.getEntityId()), f.x, f.y, f.z, f.yaw, f.pitch,
                        c == null ? 0 : c.getX(), c == null ? 0 : c.getY(), c == null ? 0 : c.getZ(), hand, c != null, w.isSneaking().orElse(false)));
                break;
            }
            case ANIMATION:
                telemetry.sample(p, SampleFactory.swing(new WrapperPlayClientAnimation(event).getHand().ordinal()));
                break;
            case PLAYER_DIGGING: {
                WrapperPlayClientPlayerDigging w = new WrapperPlayClientPlayerDigging(event);
                Vector3i bp = w.getBlockPosition();
                telemetry.sample(p, SampleFactory.block(SampleFactory.diggingAction(w.getAction().name()), bp.getX(), bp.getY(), bp.getZ(), w.getBlockFaceId(),
                        f.yaw, f.pitch, f.x, f.y, f.z, w.getSequence(), 0, 0, 0, 0, false, false));
                break;
            }
            case PLAYER_BLOCK_PLACEMENT: {
                WrapperPlayClientPlayerBlockPlacement w = new WrapperPlayClientPlayerBlockPlacement(event);
                Vector3i bp = w.getBlockPosition();
                Vector3f cur = w.getCursorPosition();
                telemetry.sample(p, SampleFactory.block(BlockAction.BLOCK_ACTION_PLACE, bp.getX(), bp.getY(), bp.getZ(), w.getFaceId(), f.yaw, f.pitch, f.x, f.y, f.z,
                        w.getSequence(), w.getHand().ordinal(), cur == null ? 0 : cur.getX(), cur == null ? 0 : cur.getY(), cur == null ? 0 : cur.getZ(),
                        w.getInsideBlock().orElse(false), w.getWorldBorderHit().orElse(false)));
                break;
            }
            case CLICK_WINDOW: {
                WrapperPlayClientClickWindow w = new WrapperPlayClientClickWindow(event);
                telemetry.sample(p, SampleFactory.inventory(w.getWindowId(), w.getSlot(), w.getButton(), SampleFactory.windowAction(w.getWindowClickType().name()),
                        w.getWindowClickType().ordinal(), w.getStateId().orElse(0), w.getSlots().map(Map::size).orElse(0),
                        w.getCarriedItemStack() != null && !w.getCarriedItemStack().isEmpty()));
                break;
            }
            case CLOSE_WINDOW:
                telemetry.sample(p, SampleFactory.inventory(new WrapperPlayClientCloseWindow(event).getWindowId(), -1, 0, "CLOSE", 0, 0, 0, false));
                break;
            case CREATIVE_INVENTORY_ACTION:
                telemetry.sample(p, SampleFactory.inventory(0, new WrapperPlayClientCreativeInventoryAction(event).getSlot(), 0, "CREATIVE_SET", 0, 0, 1, false));
                break;
            case CLIENT_STATUS:
                telemetry.sample(p, SampleFactory.clientStatus(new WrapperPlayClientClientStatus(event).getAction().ordinal()));
                break;
            case CLIENT_SETTINGS: {
                WrapperPlayClientSettings w = new WrapperPlayClientSettings(event);
                telemetry.sample(p, SampleFactory.settings(w.getViewDistance(), w.getMainHand().ordinal(), w.getVisibleSkinSectionMask()));
                break;
            }
            case SPECTATE:
                telemetry.sample(p, SampleFactory.spectate(new WrapperPlayClientSpectate(event).getTargetUUID()));
                break;
            case CHAT_MESSAGE:
                telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_CHAT, textLen(new WrapperPlayClientChatMessage(event).getMessage()), 0, false));
                break;
            case CHAT_COMMAND:
                telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_COMMAND, textLen(new WrapperPlayClientChatCommand(event).getCommand()), 0, false));
                break;
            case TAB_COMPLETE:
                telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_TAB_COMPLETE, textLen(new WrapperPlayClientTabComplete(event).getText()), 0, false));
                break;
            case EDIT_BOOK: {
                WrapperPlayClientEditBook w = new WrapperPlayClientEditBook(event);
                int max = 0;
                for (String pg : w.getPages()) max = Math.max(max, textLen(pg));
                telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_EDIT_BOOK, max, w.getPages().size(), false));
                break;
            }
            case NAME_ITEM:
                telemetry.sample(p, SampleFactory.text(TextKind.TEXT_KIND_NAME_ITEM, textLen(new WrapperPlayClientNameItem(event).getItemName()), 0, false));
                break;
            case PLUGIN_MESSAGE:
                onPluginMessage(new WrapperPlayClientPluginMessage(event), event, p);
                break;
            case PONG:
                telemetry.transactionAck(p, new WrapperPlayClientPong(event).getId());
                break;
            case WINDOW_CONFIRMATION: {
                WrapperPlayClientWindowConfirmation w = new WrapperPlayClientWindowConfirmation(event);
                if (w.getWindowId() == 0) telemetry.transactionAck(p, w.getActionId());
                break;
            }
            case TELEPORT_CONFIRM:
                telemetry.teleportConfirmed(p, new WrapperPlayClientTeleportConfirm(event).getTeleportId());
                break;
            default:
                telemetry.sample(p, SampleFactory.other(t.getId(event.getUser().getClientVersion()), t.name()));
                break;
        }
    }

    private void onFlying(WrapperPlayClientPlayerFlying w, Player p, boolean cancelled) {
        Flags f = flags(p);
        Location l = w.getLocation();
        boolean pos = w.hasPositionChanged(), rot = w.hasRotationChanged();
        if (pos) { f.x = l.getX(); f.y = l.getY(); f.z = l.getZ(); f.hasPos = true; entities.viewerAt(p.getUniqueId(), f.x, f.y, f.z); }
        if (rot) { f.yaw = l.getYaw(); f.pitch = l.getPitch(); }
        if (!pos && rot) { telemetry.sample(p, SampleFactory.rotation(l.getYaw(), l.getPitch(), w.isOnGround())); return; }
        int teleportId = pos ? telemetry.confirmsTeleport(p, l.getX(), l.getY(), l.getZ()) : 0;
        telemetry.sample(p, SampleFactory.movement(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), pos, rot, w.isOnGround(), w.isHorizontalCollision(), teleportId, cancelled));
    }

    private static boolean onGround(WrapperPlayClientVehicleMove w) {
        try { return w.isOnGround(); } catch (Throwable t) { return false; }
    }

    private static int textLen(String s) { return s == null ? 0 : s.length(); }

    private static EntityActionKind entityActionKind(WrapperPlayClientEntityAction.Action a) {
        if (a == null) return EntityActionKind.ENTITY_ACTION_UNSPECIFIED;
        switch (a) {
            case START_SNEAKING: return EntityActionKind.ENTITY_ACTION_START_SNEAK;
            case STOP_SNEAKING: return EntityActionKind.ENTITY_ACTION_STOP_SNEAK;
            case LEAVE_BED: return EntityActionKind.ENTITY_ACTION_LEAVE_BED;
            case START_SPRINTING: return EntityActionKind.ENTITY_ACTION_START_SPRINT;
            case STOP_SPRINTING: return EntityActionKind.ENTITY_ACTION_STOP_SPRINT;
            case START_JUMPING_WITH_HORSE: return EntityActionKind.ENTITY_ACTION_START_JUMP_HORSE;
            case STOP_JUMPING_WITH_HORSE: return EntityActionKind.ENTITY_ACTION_STOP_JUMP_HORSE;
            case OPEN_HORSE_INVENTORY: return EntityActionKind.ENTITY_ACTION_OPEN_HORSE_INVENTORY;
            case START_FLYING_WITH_ELYTRA: return EntityActionKind.ENTITY_ACTION_START_ELYTRA;
            default: return EntityActionKind.ENTITY_ACTION_UNSPECIFIED;
        }
    }

    private void onPluginMessage(WrapperPlayClientPluginMessage w, PacketReceiveEvent event, Player p) {
        String ch = w.getChannelName();
        int protocol = event.getUser() == null ? 0 : event.getUser().getClientVersion().getProtocolVersion();
        if (SampleFactory.isBrandChannel(ch)) telemetry.sample(p, SampleFactory.client(SampleFactory.decodeBrand(w.getData()), null, protocol));
        else if (SampleFactory.isRegisterChannel(ch)) telemetry.sample(p, SampleFactory.client("", SampleFactory.decodeRegister(w.getData()), protocol));
        else {
            byte[] data = w.getData();
            if (data != null && data.length > MAX_PAYLOAD) telemetry.sample(p, SampleFactory.payload(data.length));
        }
    }
}
