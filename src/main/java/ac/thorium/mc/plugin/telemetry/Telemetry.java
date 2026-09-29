package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.transport.ConnectionState;
import ac.thorium.mc.plugin.transport.EngineConnection;
import ac.thorium.mc.plugin.transport.HelloSupplier;
import ac.thorium.mc.plugin.capture.SampleFactory;
import ac.thorium.mc.plugin.world.WorldMirror;
import ac.thorium.mc.proto.*;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class Telemetry implements HelloSupplier {
    private final EngineConnection conn;
    private final SampleBuffer buffer;
    private final WorldMirror world;
    private final ServerCompat compat;
    private final Scheduler sched;
    private final ErrorGate gate;
    private final String pluginVersion;
    private final boolean cracked;
    private final Logger log;

    private final Map<UUID, PlayerRef> roster = new ConcurrentHashMap<>();
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerRef> byEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> pendingTeleport = new ConcurrentHashMap<>();
    private volatile OutboundCaptureHook outbound;

    public interface OutboundCaptureHook {
        void flushMoves(Player p, long tick);
        void restate(Player p);
        void forget(UUID id);
    }

    public void setOutbound(OutboundCaptureHook o) { this.outbound = o; }
    private final TransactionTracker transactions = new TransactionTracker(System::nanoTime);
    private volatile boolean transactionsEnabled;
    private volatile Boolean usePing;
    private final AtomicLong tick = new AtomicLong();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "Thorium-Flush"); t.setDaemon(true); return t; });
    private volatile ScheduledFuture<?> flushTask, heartbeatTask;
    private volatile int flushIntervalMs;
    private final java.util.concurrent.ConcurrentMap<String, CaptureWriter> captures =
        new java.util.concurrent.ConcurrentHashMap<String, CaptureWriter>();
    private final AtomicLong captureDropped = new AtomicLong();
    private static final int CAPTURE_QUEUE = 4096;
    private final ThreadPoolExecutor captureExec = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(CAPTURE_QUEUE),
            r -> { Thread t = new Thread(r, "Thorium-Capture"); t.setDaemon(true); return t; },
            (r, ex) -> captureDropped.incrementAndGet());

    public Telemetry(EngineConnection conn, SampleBuffer buffer, WorldMirror world, ServerCompat compat, Scheduler sched, ErrorGate gate,
                     PluginConfig cfg, String pluginVersion, boolean cracked, Logger log) {
        this.conn = conn; this.buffer = buffer; this.world = world; this.compat = compat; this.sched = sched; this.gate = gate;
        this.pluginVersion = pluginVersion; this.cracked = cracked; this.log = log;
        this.flushIntervalMs = clampFlush(cfg.flushIntervalMs);
    }

    public static int clampFlush(int ms) { return Math.max(25, Math.min(1000, ms)); }

    public void start() {
        scheduleFlush();
        heartbeatTask = exec.scheduleAtFixedRate(() -> gate.run("heartbeat", this::heartbeat), 10, 10, TimeUnit.SECONDS);
    }

    public void stop() {
        if (flushTask != null) flushTask.cancel(false);
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        stopCaptures();
        exec.shutdownNow();
        captureExec.shutdownNow();
        world.clear();
        roster.clear(); players.clear(); buffer.clear();
    }

    public void enableTransactions(boolean on) { transactionsEnabled = on; }

    private boolean usePing() {
        Boolean v = usePing;
        if (v == null) {
            boolean ping;
            try { ping = PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_17); }
            catch (Throwable t) { ping = true; }
            usePing = v = ping;
        }
        return v;
    }

    private void sendPing(Player p, int id) {
        PacketWrapper<?> w = usePing() ? new WrapperPlayServerPing(id) : new WrapperPlayServerWindowConfirmation(0, (short) id, false);
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(p, w);
    }

    // SENT goes into the buffer before the ping so its ACK can never be sampled first.

    public void tickTransaction(Player p) {
        if (!transactionsEnabled || !roster.containsKey(p.getUniqueId())) return;
        OutboundCaptureHook o = outbound;
        if (o != null) o.flushMoves(p, tick.get());
        int id = transactions.send(p.getUniqueId(), TransactionCause.TRANSACTION_CAUSE_TICK, 0);
        sample(p, SampleFactory.transaction(id, TransactionPhase.TRANSACTION_PHASE_SENT, TransactionCause.TRANSACTION_CAUSE_TICK, 0));
        sendPing(p, id);
    }

    public void fence(Player p, long outboundSeq) {
        if (outboundSeq < 0 || !transactionsEnabled || !roster.containsKey(p.getUniqueId())) return;
        int id = transactions.send(p.getUniqueId(), TransactionCause.TRANSACTION_CAUSE_OUTBOUND, outboundSeq);
        sample(p, SampleFactory.transaction(id, TransactionPhase.TRANSACTION_PHASE_SENT, TransactionCause.TRANSACTION_CAUSE_OUTBOUND, 0, outboundSeq));
        sendPing(p, id);
    }

    public long outbound(Player p, Outbound.Builder o) {
        return buffer.add(p.getUniqueId(), ref(p), Sample.newBuilder().setClientTimeMs(System.currentTimeMillis()).setTick(tick.get()).setOutbound(o));
    }

    public void transactionAck(Player p, int id) {
        if (!transactionsEnabled) return;
        TransactionTracker.Ack a = transactions.ack(p.getUniqueId(), id);
        if (a == null) return;
        sample(p, SampleFactory.transaction(id, TransactionPhase.TRANSACTION_PHASE_ACK, a.cause, a.rttMs, a.fencesSeq));
    }

    public void blockChangeSent(Player p, int x, int y, int z) {
        if (world.enabled()) world.invalidate(ac.thorium.mc.plugin.world.WorldSampler.sectionOf(p.getWorld().getName(), x, y, z));
    }

    public void teleportSent(Player p, int teleportId, double x, double y, double z) {
        pendingTeleport.put(p.getUniqueId(), new long[]{teleportId, Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z)});
        teleportSent(p, teleportId);
    }

    public int confirmsTeleport(Player p, double x, double y, double z) {
        long[] t = pendingTeleport.get(p.getUniqueId());
        if (t == null) return 0;
        if (Math.abs(Double.longBitsToDouble(t[1]) - x) > 1e-6
                || Math.abs(Double.longBitsToDouble(t[2]) - y) > 1e-6
                || Math.abs(Double.longBitsToDouble(t[3]) - z) > 1e-6) return 0;
        pendingTeleport.remove(p.getUniqueId());
        return (int) t[0];
    }

    public void teleportSent(Player p, int teleportId) {
        if (!transactionsEnabled || !roster.containsKey(p.getUniqueId())) return;
        try { if (!PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9)) return; } catch (Throwable t) { return; }
        sample(p, SampleFactory.transaction(teleportId, TransactionPhase.TRANSACTION_PHASE_SENT, TransactionCause.TRANSACTION_CAUSE_TELEPORT, 0));
    }

    public void teleportConfirmed(Player p, int teleportId) {
        if (!transactionsEnabled) return;
        sample(p, SampleFactory.transaction(teleportId, TransactionPhase.TRANSACTION_PHASE_ACK, TransactionCause.TRANSACTION_CAUSE_TELEPORT, 0));
    }

    private void scheduleFlush() {
        ScheduledFuture<?> old = flushTask;
        if (old != null) old.cancel(false);
        flushTask = exec.scheduleAtFixedRate(() -> gate.run("flush", this::flush), flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    private boolean send(UpStream u) {
        if (!captures.isEmpty()) {
            final UpStream frame = u;
            submitCapture(() -> { for (CaptureWriter c : captures.values()) c.write(frame); });
        }
        return conn.send(u);
    }

    private void submitCapture(Runnable r) {
        try { captureExec.execute(r); }
        catch (RejectedExecutionException e) { captureDropped.incrementAndGet(); }
    }

    private void flush() {
        Batch b = buffer.drain(System.currentTimeMillis(), tick.get(), compat.tps(), compat.mspt());
        if (b != null && !send(UpStream.newBuilder().setBatch(b).build())) buffer.clear();
        flushWorld();
    }

    private void flushWorld() {
        if (!connected() || !world.hasWork()) return;
        for (UpStream u : world.drain(System.currentTimeMillis(), tick.get(), 0)) {
            if (send(u)) continue;
            world.reset();
            return;
        }
    }

    private void heartbeat() {
        send(UpStream.newBuilder().setHeartbeat(Heartbeat.newBuilder().setClientTimeMs(System.currentTimeMillis())
                .setOnlinePlayers(roster.size()).setTps(compat.tps()).setMspt(compat.mspt())).build());
    }

    public synchronized void startCapture(String owner, java.io.File file) throws java.io.IOException {
        stopCapture(owner);
        final CaptureWriter c = new CaptureWriter(file);
        final UpStream hello = UpStream.newBuilder().setHello(buildHello()).build();
        submitCapture(() -> c.write(hello));
        captures.put(owner, c);
        if (world.enabled()) world.reset();
        snapshotAll();
    }

    public void snapshotAll() {
        for (Player p : players.values()) sched.runForPlayer(p, () -> gate.run("state:snapshot", () -> {
            inventorySnapshot(p);
            stateSnapshot(p);
            OutboundCaptureHook o = outbound;
            if (o != null && roster.containsKey(p.getUniqueId())) o.restate(p);
        }));
    }

    private static final java.lang.reflect.Method GLIDING = ac.thorium.mc.plugin.compat.Reflect.method(org.bukkit.entity.LivingEntity.class, "isGliding");
    private static final java.lang.reflect.Method SWIMMING = ac.thorium.mc.plugin.compat.Reflect.method(org.bukkit.entity.LivingEntity.class, "isSwimming");

    private static String effectKey(Object type) {
        try {
            Object key = type.getClass().getMethod("getKey").invoke(type);
            return String.valueOf(key.getClass().getMethod("getKey").invoke(key));
        } catch (Throwable t) {
            return null;
        }
    }

    public static EntityMetadata.Builder flagsOf(org.bukkit.entity.LivingEntity e) {
        return EntityMetadata.newBuilder().setId(e.getEntityId()).setHasFlags(true)
                .setSneaking(e instanceof Player && ((Player) e).isSneaking())
                .setSprinting(e instanceof Player && ((Player) e).isSprinting())
                .setSwimming(ac.thorium.mc.plugin.compat.Reflect.bool(SWIMMING, e, false))
                .setGliding(ac.thorium.mc.plugin.compat.Reflect.bool(GLIDING, e, false));
    }

    public void stateSnapshot(Player p) {
        if (!p.isOnline() || !roster.containsKey(p.getUniqueId())) return;
        int id = p.getEntityId();
        fence(p, outbound(p, Outbound.newBuilder().setEntityMetadata(flagsOf(p))));
        org.bukkit.GameMode gm = p.getGameMode();
        // Bukkit speeds are double the protocol values.
        fence(p, outbound(p, Outbound.newBuilder().setPlayerAbilities(PlayerAbilities.newBuilder().setMayFly(p.getAllowFlight())
                .setFlying(p.isFlying()).setFlySpeed(p.getFlySpeed() / 2).setWalkSpeed(p.getWalkSpeed() / 2)
                .setInvulnerable(gm == org.bukkit.GameMode.CREATIVE || gm == org.bukkit.GameMode.SPECTATOR))));
        fence(p, outbound(p, Outbound.newBuilder().setHealth(Health.newBuilder()
                .setHealth((float) p.getHealth()).setFood(p.getFoodLevel()).setSaturation(p.getSaturation()))));
        for (org.bukkit.potion.PotionEffect e : p.getActivePotionEffects()) {
            String name = effectKey(e.getType());
            if (name == null) continue;
            fence(p, outbound(p, Outbound.newBuilder().setEntityEffect(EntityEffect.newBuilder().setId(id)
                    .setEffect(name).setAmplifier(e.getAmplifier()).setDuration(e.getDuration()))));
        }
    }

    public synchronized CaptureWriter stopCapture(String owner) {
        CaptureWriter c = captures.remove(owner);
        if (c != null) closeWhenDrained(c);
        return c;
    }

    private void closeWhenDrained(final CaptureWriter c) {
        try {
            captureExec.submit(() -> c.close()).get(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            c.close();
        } catch (Throwable t) {
            c.close();
        }
    }

    public synchronized java.util.List<CaptureWriter> stopCaptures() {
        java.util.List<CaptureWriter> out = new java.util.ArrayList<CaptureWriter>();
        for (String k : new java.util.ArrayList<String>(captures.keySet())) {
            CaptureWriter c = captures.remove(k);
            if (c != null) { closeWhenDrained(c); out.add(c); }
        }
        return out;
    }

    public boolean capturing() { return !captures.isEmpty(); }

    public void track(Player p) {
        roster.put(p.getUniqueId(), Names.ref(p.getUniqueId(), p.getName(), cracked));
        players.put(p.getUniqueId(), p);
        byEntityId.put(p.getEntityId(), roster.get(p.getUniqueId()));
        sched.runForPlayer(p, () -> gate.run("inventory:snapshot", () -> inventorySnapshot(p)));
    }

    public void inventorySnapshot(Player p) {
        if (!p.isOnline() || !roster.containsKey(p.getUniqueId())) return;
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        WindowItems.Builder b = WindowItems.newBuilder().setWindow(0);
        for (int i = 0; i < 36; i++) snapshotSlot(b, i < 9 ? 36 + i : i, inv.getItem(i));
        org.bukkit.inventory.ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length && i < 4; i++) snapshotSlot(b, 8 - i, armor[i]);
        try { snapshotSlot(b, 45, inv.getItem(40)); } catch (Throwable ignored) { }
        fence(p, outbound(p, Outbound.newBuilder().setWindowItems(b)));
        fence(p, outbound(p, Outbound.newBuilder().setHeldSlot(HeldSlotOut.newBuilder().setSlot(inv.getHeldItemSlot()))));
    }

    private final java.util.Set<UUID> snapshotQueued = ConcurrentHashMap.newKeySet();

    public void inventoryChanged(Player p) {
        if (!roster.containsKey(p.getUniqueId()) || !snapshotQueued.add(p.getUniqueId())) return;
        sched.runForPlayerLater(p, () -> gate.run("inventory:snapshot", () -> {
            snapshotQueued.remove(p.getUniqueId());
            inventorySnapshot(p);
        }));
    }

    private static void snapshotSlot(WindowItems.Builder b, int protocolSlot, org.bukkit.inventory.ItemStack it) {
        if (it == null || it.getType() == org.bukkit.Material.AIR || it.getAmount() <= 0) return;
        b.addSlots(ac.thorium.mc.plugin.capture.ItemNames.slotOut(protocolSlot,
                io.github.retrooper.packetevents.util.SpigotConversionUtil.fromBukkitItemStack(it)));
    }

    public void untrack(Player p) {
        UUID id = p.getUniqueId();
        roster.remove(id); players.remove(id); buffer.remove(id); transactions.forget(id);
        byEntityId.remove(p.getEntityId()); pendingTeleport.remove(id);
        OutboundCaptureHook o = outbound;
        if (o != null) o.forget(id);
    }

    public PlayerRef ref(Player p) {
        PlayerRef r = roster.get(p.getUniqueId());
        return r != null ? r : Names.ref(p.getUniqueId(), p.getName(), cracked);
    }

    public PlayerRef refFor(int entityId) { return byEntityId.get(entityId); }

    public long tick() { return tick.get(); }

    public void advanceTick() { tick.incrementAndGet(); }

    public Iterable<Player> trackedPlayers() { return players.values(); }

    public void sample(Player p, Sample.Builder s) {
        buffer.add(p.getUniqueId(), ref(p), s.setClientTimeMs(System.currentTimeMillis()).setTick(tick.get()));
    }

    public void event(Player p, PlayerEvent.Builder e) {
        e.setPlayer(ref(p)).setClientTimeMs(System.currentTimeMillis()).setTick(tick.get());
        send(UpStream.newBuilder().setPlayerEvent(e).build());
    }

    public void applyPolicy(IngestPolicy policy) {
        if (policy.getFlushIntervalMs() > 0) {
            int ms = clampFlush(policy.getFlushIntervalMs());
            if (ms != flushIntervalMs) { flushIntervalMs = ms; scheduleFlush(); log.info("Thorium: flush interval now " + ms + " ms"); }
        }
        buffer.setEnabledCategories(policy.getEnabledCategoriesList());
        buffer.setRelativeTimes(policy.getRelativeSampleTimes());
        world.setPolicy(policy.getWorld());
    }

    public boolean connected() { return conn.state() == ConnectionState.READY; }

    public void onDisconnected() { buffer.clear(); world.reset(); }

    @Override
    public Hello buildHello() {
        return Hello.newBuilder().setPluginVersion(pluginVersion).setMcVersion(compat.mcVersion()).setSoftware(compat.software())
                .setProtocol(2).addAllRoster(roster.values()).build();
    }

    public int flushIntervalMs() { return flushIntervalMs; }

    public String statusLine() {
        StringBuilder rec = new StringBuilder();
        for (CaptureWriter c : captures.values()) {
            rec.append(rec.length() == 0 ? ", capturing " : ", ")
               .append(c.file().getName()).append(" (").append(c.frames()).append(" frames)");
        }
        long lost = captureDropped.get();
        if (lost > 0) rec.append(", ").append(lost).append(" capture frames dropped");
        return "queued players " + buffer.pending() + ", dropped " + buffer.dropped() + ", flush " + flushIntervalMs + " ms, " + roster.size() + " players, tick " + tick.get()
                + (world.enabled() ? ", world " + world.heldSections() + " sections, " + world.snapshotMicros() + " us/snapshot over " + world.snapshotCount()
                    + (ac.thorium.mc.plugin.world.SnapshotReader.emptyFastPathEnabled() ? ", empty-skip on" : ", empty-skip off")
                    + (world.syncing() ? " (syncing, " + world.pendingColumnCount() + " columns left)" : "") : "")
                + rec;
    }
}
