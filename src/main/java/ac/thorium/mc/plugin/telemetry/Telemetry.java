package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Facade the capture layer talks to: roster, sample/event stamping, flushing, heartbeat, policy. */
public final class Telemetry implements HelloSupplier {
    private final EngineConnection conn;
    private final SampleBuffer buffer;
    private final ContextTracker ctx;
    private final WorldMirror world;
    private final ServerCompat compat;
    private final Scheduler sched;
    private final ErrorGate gate;
    private final String pluginVersion;
    /**
     * Whether players here are unauthenticated. Computed once at construction
     * from online-mode *and* proxy forwarding: a backend behind a proxy runs
     * online-mode off while its players still carry real Mojang UUIDs.
     */
    private final boolean cracked;
    private final Logger log;

    private final Map<UUID, PlayerRef> roster = new ConcurrentHashMap<>();
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<UUID, MovedCounter> moved = new ConcurrentHashMap<>();
    private final TransactionTracker transactions = new TransactionTracker(System::nanoTime);
    private volatile boolean transactionsEnabled;   // packetevents loaded; markers can be sent and echoed
    private volatile Boolean usePing;               // 1.17+ servers use Ping, older ones WindowConfirmation
    private volatile ScheduledFuture<?> probeTask;
    private final AtomicLong tick = new AtomicLong();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "Thorium-Flush"); t.setDaemon(true); return t; });
    private volatile ScheduledFuture<?> flushTask, heartbeatTask;
    private volatile Object tickTask;
    private volatile int flushIntervalMs;
    private volatile CaptureWriter capture;

    public Telemetry(EngineConnection conn, SampleBuffer buffer, ContextTracker ctx, WorldMirror world, ServerCompat compat, Scheduler sched, ErrorGate gate,
                     PluginConfig cfg, String pluginVersion, boolean cracked, Logger log) {
        this.conn = conn; this.buffer = buffer; this.ctx = ctx; this.world = world; this.compat = compat; this.sched = sched; this.gate = gate;
        this.pluginVersion = pluginVersion; this.cracked = cracked; this.log = log;
        this.flushIntervalMs = clampFlush(cfg.flushIntervalMs);
    }

    public static int clampFlush(int ms) { return Math.max(25, Math.min(1000, ms)); }

    public void start() {
        tickTask = sched.runGlobalTimer(() -> { tick.incrementAndGet(); compat.onTick(System.nanoTime()); }, 1, 1);
        scheduleFlush();
        heartbeatTask = exec.scheduleAtFixedRate(() -> gate.run("heartbeat", this::heartbeat), 10, 10, TimeUnit.SECONDS);
        probeTask = exec.scheduleAtFixedRate(() -> gate.run("probe", this::probeAll), PROBE_MS, PROBE_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (flushTask != null) flushTask.cancel(false);
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        if (probeTask != null) probeTask.cancel(false);
        exec.shutdownNow();
        stopCapture();
        sched.cancel(tickTask);
        ctx.stopAll();
        world.clear();
        roster.clear(); players.clear(); moved.clear(); buffer.clear();
    }

    // ---- transactions ----

    private static final long PROBE_MS = 1000;

    /** Enables transaction markers (requires packetevents for sending pings and reading echoes). */
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

    /** Sends a ping to the client and streams the SENT marker; the echo arrives via transactionAck. */
    public void transaction(Player p, TransactionCause cause) {
        if (!transactionsEnabled || !roster.containsKey(p.getUniqueId())) return;
        int id = transactions.send(p.getUniqueId(), cause);
        PacketWrapper<?> w = usePing() ? new WrapperPlayServerPing(id) : new WrapperPlayServerWindowConfirmation(0, (short) id, false);
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(p, w);
        sample(p, SampleFactory.transaction(id, TransactionPhase.TRANSACTION_PHASE_SENT, cause, 0));
    }

    /** Client echoed one of our pings (Pong / WindowConfirmation). Ids outside our range are the server's own. */
    public void transactionAck(Player p, int id) {
        if (!transactionsEnabled) return;
        TransactionTracker.Ack a = transactions.ack(p.getUniqueId(), id);
        if (a == null) return;
        sample(p, SampleFactory.transaction(id, TransactionPhase.TRANSACTION_PHASE_ACK, a.cause, a.rttMs));
    }

    /** A teleport packet was written; the client's TELEPORT_CONFIRM (1.9+) is its ACK. 1.8 has no confirm, so no marker. */
    public void teleportSent(Player p, int teleportId) {
        if (!transactionsEnabled || !roster.containsKey(p.getUniqueId())) return;
        try { if (!PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9)) return; } catch (Throwable t) { return; }
        sample(p, SampleFactory.transaction(teleportId, TransactionPhase.TRANSACTION_PHASE_SENT, TransactionCause.TRANSACTION_CAUSE_TELEPORT, 0));
    }

    public void teleportConfirmed(Player p, int teleportId) {
        if (!transactionsEnabled) return;
        sample(p, SampleFactory.transaction(teleportId, TransactionPhase.TRANSACTION_PHASE_ACK, TransactionCause.TRANSACTION_CAUSE_TELEPORT, 0));
    }

    /** One latency probe per player per second: cheap, and gives the engine a per-player RTT it can trust. */
    private void probeAll() {
        if (!transactionsEnabled || conn.state() != ac.thorium.mc.plugin.transport.ConnectionState.READY) return;
        for (Player p : players.values()) {
            if (!p.isOnline()) continue;
            if (transactions.pending(p.getUniqueId()) > 64) continue;   // client not answering; don't pile up
            transaction(p, TransactionCause.TRANSACTION_CAUSE_PROBE);
        }
    }

    private void scheduleFlush() {
        ScheduledFuture<?> old = flushTask;
        if (old != null) old.cancel(false);
        flushTask = exec.scheduleAtFixedRate(() -> gate.run("flush", this::flush), flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** Sends to the engine and mirrors the frame into the dev capture file when recording. */
    private boolean send(UpStream u) {
        CaptureWriter c = capture;
        if (c != null) c.write(u);
        return conn.send(u);
    }

    private void flush() {
        Batch b = buffer.drain(System.currentTimeMillis());
        if (b != null && !send(UpStream.newBuilder().setBatch(b).build())) buffer.clear();
        flushWorld();
    }

    /**
     * Packages whatever the world sampler has produced. Runs on the flush worker,
     * never on a server thread: the sections were already decoded off-thread and
     * this only serializes and writes them.
     */
    private void flushWorld() {
        if (!world.hasWork()) return;
        for (UpStream u : world.drain(System.currentTimeMillis(), tick.get(), 0)) {
            if (!send(u)) break;
        }
    }

    private void heartbeat() {
        send(UpStream.newBuilder().setHeartbeat(Heartbeat.newBuilder().setClientTimeMs(System.currentTimeMillis())
                .setOnlinePlayers(roster.size()).setTps(compat.tps()).setMspt(compat.mspt())).build());
    }

    /** Start recording to file (writes a Hello first so replays carry the roster); stops any previous recording. */
    public synchronized void startCapture(java.io.File file) throws java.io.IOException {
        stopCapture();
        CaptureWriter c = new CaptureWriter(file);
        c.write(UpStream.newBuilder().setHello(buildHello()).build());
        capture = c;
        // Re-sync the world so the recording carries the terrain from its first
        // frame. A capture started mid-connection would otherwise only see the
        // sections the player newly walks into, and anything replaying it would
        // judge movement over ground it cannot see.
        if (world.enabled()) world.reset();
    }

    public synchronized CaptureWriter stopCapture() {
        CaptureWriter c = capture;
        capture = null;
        if (c != null) c.close();
        return c;
    }

    public CaptureWriter capture() { return capture; }

    public void track(Player p) {
        roster.put(p.getUniqueId(), Names.ref(p.getUniqueId(), p.getName(), cracked));
        players.put(p.getUniqueId(), p);
        moved.put(p.getUniqueId(), new MovedCounter(3));
        ctx.start(p);
    }

    public void untrack(Player p) {
        UUID id = p.getUniqueId();
        roster.remove(id); players.remove(id); moved.remove(id); buffer.remove(id); ctx.stop(p); transactions.forget(id);
    }

    public PlayerRef ref(Player p) {
        PlayerRef r = roster.get(p.getUniqueId());
        return r != null ? r : Names.ref(p.getUniqueId(), p.getName(), cracked);
    }

    public PlayerContext context(Player p) { return ctx.get(p.getUniqueId()); }
    public long tick() { return tick.get(); }
    public void markMoved(Player p) { MovedCounter m = moved.get(p.getUniqueId()); if (m != null) m.mark(); }

    public void sample(Player p, Sample.Builder s) { sample(p, s, System.currentTimeMillis(), tick.get()); }

    /** clientTimeMs/tick are taken at capture time so samples completed later on the player's thread keep their true timing. */
    public void sample(Player p, Sample.Builder s, long clientTimeMs, long capturedTick) {
        UUID id = p.getUniqueId();
        boolean movement = s.getKindCase() == Sample.KindCase.MOVEMENT || s.getKindCase() == Sample.KindCase.ROTATION;
        MovedCounter m = moved.get(id);
        boolean serverMoved = movement && m != null && m.consume();
        s.setClientTimeMs(clientTimeMs).setTick(capturedTick).setMeta(MetaBuilder.build(ctx.get(id), serverMoved));
        buffer.add(id, ref(p), s.build());
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
        world.setPolicy(policy.getWorld());
    }

    /** Arms a fresh world sync: whatever the engine held is gone with the connection. */
    public void onDisconnected() { buffer.clear(); world.reset(); }

    @Override
    public Hello buildHello() {
        return Hello.newBuilder().setPluginVersion(pluginVersion).setMcVersion(compat.mcVersion()).setSoftware(compat.software())
                .setProtocol(1).addAllRoster(roster.values()).build();
    }

    public int flushIntervalMs() { return flushIntervalMs; }

    public String statusLine() {
        CaptureWriter c = capture;
        return "queue " + buffer.pending() + ", dropped " + buffer.dropped() + ", flush " + flushIntervalMs + " ms, " + roster.size() + " players, tick " + tick.get()
                + ", ctx " + ctx.contextNanos() + " ns over " + ctx.contextCount()
                + (world.enabled() ? ", world " + world.heldSections() + " sections, " + world.snapshotMicros() + " us/snapshot over " + world.snapshotCount()
                    + (ac.thorium.mc.plugin.world.SnapshotReader.emptyFastPathEnabled() ? ", empty-skip on" : ", empty-skip off")
                    + (world.syncing() ? " (syncing, " + world.pendingColumnCount() + " columns left)" : "") : "")
                + (c == null ? "" : ", capturing " + c.file().getName() + " (" + c.frames() + " frames)");
    }
}
