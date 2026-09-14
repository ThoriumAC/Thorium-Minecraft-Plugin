package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Reflect;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.telemetry.PlayerContext;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.proto.BlockAction;
import ac.thorium.mc.proto.CombatAction;
import ac.thorium.mc.proto.PlayerRef;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Turns client packets into Samples. MONITOR priority, never cancels, never touches Bukkit off-thread. */
public final class PacketCapture extends PacketListenerAbstract {
    private static final Method ENTITY_WIDTH = Reflect.method(Entity.class, "getWidth");
    private static final Method ENTITY_HEIGHT = Reflect.method(Entity.class, "getHeight");
    private static final Method BREAK_SPEED = Reflect.method(Block.class, "getBreakSpeed", Player.class);

    private static final class Flags { volatile boolean sprinting, sneaking, hasPos; volatile double x, y, z; volatile float yaw, pitch; }

    private final Telemetry telemetry;
    private final Scheduler sched;
    private final ErrorGate gate;
    private final Map<UUID, Flags> flags = new ConcurrentHashMap<>();

    public PacketCapture(Telemetry telemetry, Scheduler sched, ErrorGate gate) {
        super(PacketListenerPriority.MONITOR);
        this.telemetry = telemetry; this.sched = sched; this.gate = gate;
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
        gate.run("capture:" + t.name(), () -> dispatch(t, event, p));
    }

    private void dispatch(PacketType.Play.Client t, PacketReceiveEvent event, Player p) {
        switch (t) {
            case PLAYER_FLYING: case PLAYER_POSITION: case PLAYER_POSITION_AND_ROTATION: case PLAYER_ROTATION: onFlying(new WrapperPlayClientPlayerFlying(event), p); break;
            case ENTITY_ACTION: onEntityAction(new WrapperPlayClientEntityAction(event), p); break;
            case PLAYER_INPUT: { WrapperPlayClientPlayerInput w = new WrapperPlayClientPlayerInput(event); Flags f = flags(p); f.sneaking = w.isShift(); f.sprinting = w.isSprint(); break; }
            case INTERACT_ENTITY: onInteract(new WrapperPlayClientInteractEntity(event), p); break;
            case ANIMATION: onSwing(p); break;
            case PLAYER_DIGGING: onDigging(new WrapperPlayClientPlayerDigging(event), p); break;
            case PLAYER_BLOCK_PLACEMENT: { Vector3i bp = new WrapperPlayClientPlayerBlockPlacement(event).getBlockPosition(); blockSample(p, BlockAction.BLOCK_ACTION_PLACE, bp.getX(), bp.getY(), bp.getZ(), new WrapperPlayClientPlayerBlockPlacement(event).getFaceId()); break; }
            case CLICK_WINDOW: { WrapperPlayClientClickWindow w = new WrapperPlayClientClickWindow(event); telemetry.sample(p, SampleFactory.inventory(w.getWindowId(), w.getSlot(), w.getButton(), SampleFactory.windowAction(w.getWindowClickType().name()))); break; }
            case CLOSE_WINDOW: telemetry.sample(p, SampleFactory.inventory(new WrapperPlayClientCloseWindow(event).getWindowId(), -1, 0, "CLOSE")); break;
            case PLUGIN_MESSAGE: onPluginMessage(new WrapperPlayClientPluginMessage(event), event, p); break;
            // Transaction echoes: streamed at their position in the sample stream so the engine
            // knows exactly which movement packets came after the client applied a server-side move.
            case PONG: telemetry.transactionAck(p, new WrapperPlayClientPong(event).getId()); break;
            case WINDOW_CONFIRMATION: { WrapperPlayClientWindowConfirmation w = new WrapperPlayClientWindowConfirmation(event); if (w.getWindowId() == 0) telemetry.transactionAck(p, w.getActionId()); break; }
            case TELEPORT_CONFIRM: telemetry.teleportConfirmed(p, new WrapperPlayClientTeleportConfirm(event).getTeleportId()); break;
            default: break;
        }
    }

    private void onFlying(WrapperPlayClientPlayerFlying w, Player p) {
        Flags f = flags(p);
        Location l = w.getLocation();
        boolean pos = w.hasPositionChanged(), rot = w.hasRotationChanged();
        if (pos) { f.x = l.getX(); f.y = l.getY(); f.z = l.getZ(); f.hasPos = true; }
        if (rot) { f.yaw = l.getYaw(); f.pitch = l.getPitch(); }
        if (!pos && rot) { telemetry.sample(p, SampleFactory.rotation(l.getYaw(), l.getPitch(), w.isOnGround())); return; }
        telemetry.sample(p, SampleFactory.movement(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), pos, rot, w.isOnGround(), f.sprinting, f.sneaking, w.isHorizontalCollision()));
    }

    private void onEntityAction(WrapperPlayClientEntityAction w, Player p) {
        Flags f = flags(p);
        switch (w.getAction().name()) {
            case "START_SPRINTING": f.sprinting = true; break;
            case "STOP_SPRINTING": f.sprinting = false; break;
            case "START_SNEAKING": f.sneaking = true; break;
            case "STOP_SNEAKING": f.sneaking = false; break;
            default: break;
        }
    }

    private double[] attackerPos(Player p) {
        Flags f = flags(p);
        if (f.hasPos) return new double[]{f.x, f.y, f.z, f.yaw, f.pitch};
        PlayerContext c = telemetry.context(p);
        return new double[]{c.x, c.y, c.z, c.yaw, c.pitch};
    }

    private void onInteract(WrapperPlayClientInteractEntity w, Player p) {
        CombatAction action = w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK ? CombatAction.COMBAT_ACTION_ATTACK : CombatAction.COMBAT_ACTION_INTERACT;
        int targetId = w.getEntityId();
        double[] a = attackerPos(p);
        long at = System.currentTimeMillis(), tick = telemetry.tick();
        sched.runForPlayer(p, () -> gate.run("capture:interact-fill", () -> {
            Entity target = SpigotConversionUtil.getEntityById(p.getWorld(), targetId);
            double tx = 0, ty = 0, tz = 0; float width = 0, height = 0; boolean isPlayer = false; PlayerRef ref = null;
            if (target != null) {
                org.bukkit.Location tl = target.getLocation();
                tx = tl.getX(); ty = tl.getY(); tz = tl.getZ();
                isPlayer = target instanceof Player;
                Object wv = Reflect.invoke(ENTITY_WIDTH, target), hv = Reflect.invoke(ENTITY_HEIGHT, target);
                width = wv instanceof Double ? ((Double) wv).floatValue() : (isPlayer ? 0.6f : 0f);
                height = hv instanceof Double ? ((Double) hv).floatValue() : (isPlayer ? 1.8f : 0f);
                if (isPlayer) ref = telemetry.ref((Player) target);
            }
            telemetry.sample(p, SampleFactory.combat(action, targetId, ref, a[0], a[1], a[2], (float) a[3], (float) a[4], tx, ty, tz, width, height, isPlayer), at, tick);
        }));
    }

    private void onSwing(Player p) {
        double[] a = attackerPos(p);
        long at = System.currentTimeMillis(), tick = telemetry.tick();
        // Same hop as attacks so swing/hit order inside a batch matches capture order.
        sched.runForPlayer(p, () -> gate.run("capture:swing", () ->
                telemetry.sample(p, SampleFactory.combat(CombatAction.COMBAT_ACTION_SWING, 0, null, a[0], a[1], a[2], (float) a[3], (float) a[4], 0, 0, 0, 0f, 0f, false), at, tick)));
    }

    private void onDigging(WrapperPlayClientPlayerDigging w, Player p) {
        BlockAction action = SampleFactory.diggingAction(w.getAction().name());
        if (action == null) return;
        Vector3i bp = w.getBlockPosition();
        blockSample(p, action, bp.getX(), bp.getY(), bp.getZ(), w.getBlockFaceId());
    }

    private void blockSample(Player p, BlockAction action, int x, int y, int z, int face) {
        Flags f = flags(p);
        double[] a = attackerPos(p);
        boolean sneaking = f.sneaking, sprinting = f.sprinting;
        long at = System.currentTimeMillis(), tick = telemetry.tick();
        sched.runForPlayer(p, () -> gate.run("capture:block-fill", () -> {
            Block b = p.getWorld().getBlockAt(x, y, z);
            String id = b.getType().name().toLowerCase(Locale.ROOT);
            int ticks = 0;
            if (action == BlockAction.BLOCK_ACTION_DIG_START) {
                Object speed = Reflect.invoke(BREAK_SPEED, b, p);
                if (speed instanceof Float && (Float) speed > 0f) ticks = (int) Math.ceil(1.0 / (Float) speed);
            }
            telemetry.sample(p, SampleFactory.block(action, x, y, z, face, id, (float) a[3], (float) a[4], a[0], a[1], a[2], ticks, sneaking, sprinting), at, tick);
        }));
    }

    private void onPluginMessage(WrapperPlayClientPluginMessage w, PacketReceiveEvent event, Player p) {
        String ch = w.getChannelName();
        int protocol = event.getUser() == null ? 0 : event.getUser().getClientVersion().getProtocolVersion();
        if (SampleFactory.isBrandChannel(ch)) telemetry.sample(p, SampleFactory.client(SampleFactory.decodeBrand(w.getData()), null, protocol));
        else if (SampleFactory.isRegisterChannel(ch)) telemetry.sample(p, SampleFactory.client("", SampleFactory.decodeRegister(w.getData()), protocol));
    }
}
