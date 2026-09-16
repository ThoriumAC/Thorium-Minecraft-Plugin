package ac.thorium.mc.plugin;

import ac.thorium.mc.plugin.capture.BukkitEvents;
import ac.thorium.mc.plugin.capture.PacketCapture;
import ac.thorium.mc.plugin.capture.PacketSend;
import ac.thorium.mc.plugin.command.ThoriumCommand;
import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Reflect;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.enforce.Enforcer;
import ac.thorium.mc.plugin.enforce.StaffAlerts;
import ac.thorium.mc.plugin.telemetry.ContextTracker;
import ac.thorium.mc.plugin.telemetry.SampleBuffer;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.*;
import ac.thorium.mc.plugin.world.SnapshotReader;
import ac.thorium.mc.plugin.world.WorldBlockEvents;
import ac.thorium.mc.plugin.world.WorldMirror;
import ac.thorium.mc.plugin.world.WorldSampler;
import ac.thorium.mc.proto.HelloAck;
import ac.thorium.mc.proto.IngestPolicy;
import ac.thorium.mc.proto.PluginUpdate;
import ac.thorium.mc.proto.Verdict;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public final class ThoriumPlugin extends JavaPlugin {
    private PluginConfig cfg;
    private ErrorGate gate;
    private Scheduler sched;
    private ServerCompat compat;
    private StaffAlerts alerts;
    private Enforcer enforcer;
    private Telemetry telemetry;
    private EngineConnection connection;
    private PacketCapture capture;
    private PacketSend send;
    private BukkitEvents events;
    private WorldMirror world;
    private WorldSampler worldSampler;
    private WorldBlockEvents worldEvents;
    private boolean packetEventsReady;

    @Override
    public void onLoad() {
        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            PacketEvents.getAPI().getSettings().checkForUpdates(false).reEncodeByDefault(false).debug(false);
            PacketEvents.getAPI().load();
            packetEventsReady = true;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Thorium: packetevents failed to load; capture disabled", t);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = PluginConfig.from(getConfig());
        gate = new ErrorGate(getLogger(), 60_000, System::currentTimeMillis);
        sched = Scheduler.create(this);
        compat = new ServerCompat(getServer());
        alerts = new StaffAlerts();
        ThoriumCommand command = new ThoriumCommand(this);
        getCommand("thorium").setExecutor(command);
        getCommand("thorium").setTabCompleter(command);
        if (packetEventsReady) {
            // init() once per enable, independent of config, so players who join while unconfigured are injected too.
            try { PacketEvents.getAPI().init(); } catch (Throwable t) { packetEventsReady = false; getLogger().log(Level.SEVERE, "Thorium: packetevents init failed; capture disabled", t); }
        }
        if (!cfg.isConfigured()) {
            getLogger().severe("Thorium: server-token is empty in plugins/Thorium/config.yml - the plugin is idle until configured (/thorium reconnect after editing).");
            return;
        }
        startPipeline();
        getLogger().info("Thorium " + getDescription().getVersion() + " enabled on " + compat.software().name().replace("SERVER_SOFTWARE_", "") + " " + compat.mcVersion() + (sched.isFolia() ? " (Folia)" : ""));
        logCompatReport();
    }

    /**
     * Writes the compat report to the server log once, at enable.
     *
     * <p>Every version-adaptive lookup in this plugin fails silently: a missing
     * method yields a null handle, the mirror streams air, and the engine goes
     * on judging players against ground it cannot see. {@code /thorium compat}
     * exists to say which of those has happened, but it has to be typed by
     * somebody who already suspects it - and on a version nobody has run the
     * plugin against, nobody does. The log line is free and always there.
     *
     * <p>Held sections are necessarily zero this early. The line that matters
     * at enable is which reflection branch each lookup resolved to, which is
     * fixed by the server's API and will not change while it runs.
     */
    private void logCompatReport() {
        for (String line : ThoriumCommand.compatLines(getDescription().getVersion(),
                compat.software().name().replace("SERVER_SOFTWARE_", ""), compat.mcVersion(),
                sched.isFolia(), System.getProperty("java.version", "?"),
                getServer().getOnlineMode(), compat.proxyForwarding(),
                world != null && world.enabled(), world == null ? 0 : world.heldSections(),
                SnapshotReader.branches())) {
            getLogger().info(line.replaceAll("\u00a7.", "").trim());
        }
    }

    /**
     * Listens for riptide, the other push a player may legitimately give
     * themselves in the air.
     *
     * <p>Registered by name rather than by a typed handler: this plugin
     * compiles against the 1.8 API so that it cannot reach for anything newer
     * by accident, and PlayerRiptideEvent arrived in 1.13. Bukkit resolves a
     * handler method's parameter type when it registers it, so a typed
     * listener for a class this server does not have would fail - taking every
     * other handler in the same class with it.
     */
    private void registerRiptide() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends org.bukkit.event.Event> cls =
                    (Class<? extends org.bukkit.event.Event>) Class.forName("org.bukkit.event.player.PlayerRiptideEvent");
            final java.lang.reflect.Method getPlayer = cls.getMethod("getPlayer");
            org.bukkit.event.Listener holder = new org.bukkit.event.Listener() {};
            getServer().getPluginManager().registerEvent(cls, holder, org.bukkit.event.EventPriority.MONITOR,
                    new org.bukkit.plugin.EventExecutor() {
                        @Override public void execute(org.bukkit.event.Listener l, org.bukkit.event.Event e) {
                            gate.run("event:riptide", () -> {
                                Object p = Reflect.invoke(getPlayer, e);
                                if (p instanceof org.bukkit.entity.Player && telemetry != null) {
                                    telemetry.event((org.bukkit.entity.Player) p, ac.thorium.mc.plugin.capture.EventFactory.boost("riptide"));
                                }
                            });
                        }
                    }, this, true);
        } catch (Throwable t) {
            getLogger().fine("Thorium: no riptide event on this server; elytra boosts from a trident will not be reported");
        }
    }

    private void startPipeline() {
        String version = getDescription().getVersion();
        SampleBuffer buffer = new SampleBuffer(400);
        enforcer = new Enforcer(getServer(), sched, compat, gate, alerts, cfg, getLogger());
        world = new WorldMirror();
        // When the engine is mirroring the world it derives the block flags itself,
        // so the context tracker can skip its per-tick block reads entirely.
        ContextTracker contexts = new ContextTracker(sched, compat, gate,
                () -> telemetry == null ? 0L : telemetry.tick(), world::enabled);
        ConnectionConfig ccfg = new ConnectionConfig(cfg.gatewayUrl, cfg.devSessionToken, version);
        TokenSource tokens = new SessionAuth(cfg.gatewayUrl, cfg.serverToken, version, 10_000);
        connection = new EngineConnection(ccfg, tokens, () -> telemetry.buildHello(), new Handler(), getLogger());
        // Not getOnlineMode() on its own: behind a proxy it is off by design while
        // the UUIDs are real, so asking ServerCompat keeps a whole proxied network
        // from being reported as unauthenticated.
        telemetry = new Telemetry(connection, buffer, contexts, world, compat, sched, gate, cfg, version, compat.cracked(getServer().getOnlineMode()), getLogger());
        if (packetEventsReady) {
            capture = new PacketCapture(telemetry, sched, gate);
            PacketEvents.getAPI().getEventManager().registerListener(capture);
            send = new PacketSend(telemetry, gate);
            PacketEvents.getAPI().getEventManager().registerListener(send);
            telemetry.enableTransactions(true);
        }
        events = new BukkitEvents(telemetry, capture, gate, cfg.sendIp);
        getServer().getPluginManager().registerEvents(events, this);
        registerRiptide();
        // World streaming stays dormant until the engine's IngestPolicy turns it on,
        // so upgrading the plugin never costs a server TPS on its own.
        worldEvents = new WorldBlockEvents(world, gate);
        getServer().getPluginManager().registerEvents(worldEvents, this);
        worldSampler = new WorldSampler(world, sched, gate, getServer());
        worldSampler.start();
        for (Player p : getServer().getOnlinePlayers()) telemetry.track(p);
        telemetry.start();
        connection.start();
    }

    private void stopPipeline() {
        if (connection != null) connection.stop();
        if (worldSampler != null) worldSampler.stop();
        if (worldEvents != null) HandlerList.unregisterAll(worldEvents);
        if (telemetry != null) telemetry.stop();
        if (capture != null && packetEventsReady) PacketEvents.getAPI().getEventManager().unregisterListener(capture);
        if (send != null && packetEventsReady) PacketEvents.getAPI().getEventManager().unregisterListener(send);
        if (events != null) HandlerList.unregisterAll(events);
        connection = null; telemetry = null; capture = null; send = null; events = null;
        worldSampler = null; worldEvents = null; world = null;
    }

    /** /thorium reconnect: re-read config and rebuild the whole pipeline so every key takes effect. */
    public void reconnect() {
        reloadConfig();
        cfg = PluginConfig.from(getConfig());
        stopPipeline();
        if (!cfg.isConfigured()) { getLogger().severe("Thorium: still not configured."); return; }
        startPipeline();
    }

    @Override
    public void onDisable() {
        stopPipeline();
        if (packetEventsReady) { try { PacketEvents.getAPI().terminate(); } catch (Throwable ignored) {} }
        if (sched != null) sched.cancelAll();
    }

    public EngineConnection connection() { return connection; }
    public Telemetry telemetry() { return telemetry; }
    public StaffAlerts staffAlerts() { return alerts; }
    public ServerCompat compat() { return compat; }
    public Scheduler scheduler() { return sched; }
    public PluginConfig config() { return cfg; }
    public WorldMirror world() { return world; }

    private final class Handler implements DownstreamHandler {
        @Override public void onHelloAck(HelloAck ack) {
            // The handshake carries the org's starting policy, world streaming included.
            Telemetry t = telemetry;
            if (t != null && ack.hasPolicy()) t.applyPolicy(ack.getPolicy());
        }
        @Override public void onVerdict(Verdict v) { enforcer.accept(v); }
        @Override public void onPolicy(IngestPolicy p) { Telemetry t = telemetry; if (t != null) t.applyPolicy(p); }
        @Override public void onPluginUpdate(PluginUpdate u) { getLogger().warning("Thorium: plugin update available: " + u.getVersion() + " " + u.getDownloadUrl()); }
        @Override public void onGatewayControl(int opcode, byte[] payload) {
            if (opcode == FrameDiscriminator.GATEWAY_MESSAGE) getLogger().info("Thorium gateway: " + new String(payload, StandardCharsets.UTF_8));
            else if (opcode == FrameDiscriminator.GATEWAY_MOD_UPDATE) getLogger().warning("Thorium gateway: update notice");
        }
        @Override public void onStateChange(ConnectionState from, ConnectionState to) {
            Level lvl = (to == ConnectionState.READY || to == ConnectionState.HELD || to == ConnectionState.STOPPED) ? Level.INFO : Level.FINE;
            getLogger().log(lvl, "Thorium: connection " + from + " -> " + to);
        }
        @Override public void onDisconnected() { Telemetry t = telemetry; if (t != null) t.onDisconnected(); }
    }
}
