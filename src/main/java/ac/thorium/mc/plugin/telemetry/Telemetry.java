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
    // Recordings in progress, keyed by whoever asked for one.
    //
    // There used to be one. Starting a capture stopped the previous, which is
    // fine for a person debugging one player and wrong for anything with more
    // than one client on the server: a compatibility run starts seventeen
    // captures within a few seconds of each other and kept only the last,
    // leaving sixteen files holding a second or two each. Twice now a failure
    // that only happens under load could not be read back, because the only
    // recording of it was a fragment.
    private final java.util.concurrent.ConcurrentMap<String, CaptureWriter> captures =
        new java.util.concurrent.ConcurrentHashMap<String, CaptureWriter>();
    /** Frames a full capture queue had to throw away, and how many. */
    private final AtomicLong captureDropped = new AtomicLong();
    /**
     * Where capture frames are written.
     *
     * <p>They used to be written inline, inside {@link #send}, which is reached
     * from {@link #event} - and that runs in main-thread Bukkit handlers. For as
     * long as a capture was running the server thread did FileOutputStream
     * writes and contended with the flush worker for the writer's lock, on a
     * command an admin is invited to run on a live server.
     *
     * <p>One thread, so frames keep the order they were sent in. Bounded, so a
     * disk that cannot keep up costs a recording rather than the heap; what it
     * cost is counted and shown in /thorium status. Started lazily: a server
     * that never captures never gets the thread.
     */
    private static final int CAPTURE_QUEUE = 4096;
    private final ThreadPoolExecutor captureExec = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(CAPTURE_QUEUE),
            r -> { Thread t = new Thread(r, "Thorium-Capture"); t.setDaemon(true); return t; },
            (r, ex) -> captureDropped.incrementAndGet());

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
        // Before the executors go: closing a capture drains its queued frames.
        stopCaptures();
        exec.shutdownNow();
        captureExec.shutdownNow();
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
        if (!captures.isEmpty()) {
            final UpStream frame = u;
            submitCapture(() -> { for (CaptureWriter c : captures.values()) c.write(frame); });
        }
        return conn.send(u);
    }

    /** Queues capture work; the caller's thread never touches the file. */
    private void submitCapture(Runnable r) {
        try { captureExec.execute(r); }
        catch (RejectedExecutionException e) { captureDropped.incrementAndGet(); }
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

    /**
     * Start recording to file under {@code owner}, writing a Hello first so
     * replays carry the roster. Replaces only that owner's own recording;
     * anyone else's keeps running.
     */
    public synchronized void startCapture(String owner, java.io.File file) throws java.io.IOException {
        stopCapture(owner);
        final CaptureWriter c = new CaptureWriter(file);
        final UpStream hello = UpStream.newBuilder().setHello(buildHello()).build();
        // Queued before the writer is published, so this is the first frame in
        // the file and no other owner's recording sees it.
        submitCapture(() -> c.write(hello));
        captures.put(owner, c);
        // Re-sync the world so the recording carries the terrain from its first
        // frame. A capture started mid-connection would otherwise only see the
        // sections the player newly walks into, and anything replaying it would
        // judge movement over ground it cannot see.
        if (world.enabled()) world.reset();
    }

    /** Stops and returns {@code owner}'s recording, or null if they had none. */
    public synchronized CaptureWriter stopCapture(String owner) {
        CaptureWriter c = captures.remove(owner);
        if (c != null) closeWhenDrained(c);
        return c;
    }

    /**
     * Closes a writer behind whatever is still queued for it, so the frames sent
     * in the moment before "capture stop" reach the file and the frame count
     * reported back is the real one. Bounded: a disk that has stopped answering
     * must not hold the command thread.
     */
    private void closeWhenDrained(final CaptureWriter c) {
        try {
            captureExec.submit(() -> c.close()).get(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            c.close();
        } catch (Throwable t) {
            c.close();   // rejected or too slow: close here and lose the tail
        }
    }

    /** Stops every recording, for shutdown and for a console "capture stop". */
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
        StringBuilder rec = new StringBuilder();
        for (CaptureWriter c : captures.values()) {
            rec.append(rec.length() == 0 ? ", capturing " : ", ")
               .append(c.file().getName()).append(" (").append(c.frames()).append(" frames)");
        }
        long lost = captureDropped.get();
        if (lost > 0) rec.append(", ").append(lost).append(" capture frames dropped");
        return "queue " + buffer.pending() + ", dropped " + buffer.dropped() + ", flush " + flushIntervalMs + " ms, " + roster.size() + " players, tick " + tick.get()
                + ", ctx " + ctx.contextNanos() + " ns over " + ctx.contextCount()
                + (world.enabled() ? ", world " + world.heldSections() + " sections, " + world.snapshotMicros() + " us/snapshot over " + world.snapshotCount()
                    + (ac.thorium.mc.plugin.world.SnapshotReader.emptyFastPathEnabled() ? ", empty-skip on" : ", empty-skip off")
                    + (world.syncing() ? " (syncing, " + world.pendingColumnCount() + " columns left)" : "") : "")
                + rec;
    }
}
