package ac.thorium.mc.plugin;

import ac.thorium.mc.plugin.capture.BukkitEvents;
import ac.thorium.mc.plugin.capture.PacketCapture;
import ac.thorium.mc.plugin.capture.EntityTracker;
import ac.thorium.mc.plugin.telemetry.TickTask;
import ac.thorium.mc.plugin.capture.OutboundCapture;
import ac.thorium.mc.plugin.command.ThoriumCommand;
import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.PacketEventsVersions;
import ac.thorium.mc.plugin.compat.Reflect;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.enforce.Enforcer;
import ac.thorium.mc.plugin.enforce.Mitigator;
import ac.thorium.mc.plugin.enforce.StaffAlerts;
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
import ac.thorium.mc.proto.Mitigate;
import ac.thorium.mc.proto.Verdict;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
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
    private ac.thorium.mc.plugin.config.NetworkSettings settings;
    private Telemetry telemetry;
    private EngineConnection connection;
    private PacketCapture capture;
    private OutboundCapture outbound;
    private Mitigator mitigator;
    private TickTask tickTask;
    private EntityTracker entityTracker;
    private BukkitEvents events;
    private ac.thorium.mc.plugin.capture.ActivityEvents activity;
    private WorldMirror world;
    private WorldSampler worldSampler;
    private WorldBlockEvents worldEvents;
    private boolean packetEventsReady;
    private boolean paperBoostsAvailable;

    @Override
    public void onLoad() {
        try {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
            PacketEvents.getAPI().getSettings().checkForUpdates(false).reEncodeByDefault(false).debug(false);
            ServerVersion pinned = PacketEventsVersions.pinIfUnknown(getServer().getBukkitVersion());
            if (pinned != null) {
                getLogger().warning("Thorium: packetevents does not know " + getServer().getBukkitVersion()
                        + "; pinning it to " + pinned.getReleaseName() + " so capture still loads");
            }
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
        registerRiptide();
        registerElytraBoost();
        ThoriumCommand command = new ThoriumCommand(this);
        getCommand("thorium").setExecutor(command);
        getCommand("thorium").setTabCompleter(command);
        ac.thorium.mc.plugin.command.ReportCommand report = new ac.thorium.mc.plugin.command.ReportCommand(() -> settings, () -> connection, () -> telemetry);
        getCommand("report").setExecutor(report);
        getCommand("report").setTabCompleter(report);
        if (packetEventsReady) {
            try { PacketEvents.getAPI().init(); } catch (Throwable t) { packetEventsReady = false; getLogger().log(Level.SEVERE, "Thorium: packetevents init failed; capture disabled", t); }
        }
        if (!cfg.isConfigured()) {
            getLogger().severe("Thorium: server-token is empty in plugins/Thorium/config.yml - the plugin is idle until configured (/thorium reconnect after editing).");
            return;
        }
        if (cfg.needsServerName()) {
            getLogger().severe("Thorium: server-token is a network token, so server-name must say which server this is. "
                    + "Set server-name in plugins/Thorium/config.yml to a server on this network, then /thorium reconnect.");
            return;
        }
        if (!gatewayUsable()) return;
        startPipeline();
        getLogger().info("Thorium " + getDescription().getVersion() + " enabled on " + compat.software().name().replace("SERVER_SOFTWARE_", "") + " " + compat.mcVersion() + (sched.isFolia() ? " (Folia)" : ""));
        logCompatReport();
    }

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

    private void registerElytraBoost() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends org.bukkit.event.Event> cls =
                    (Class<? extends org.bukkit.event.Event>) Class.forName("com.destroystokyo.paper.event.player.PlayerElytraBoostEvent");
            final java.lang.reflect.Method getPlayer = cls.getMethod("getPlayer");
            org.bukkit.event.Listener holder = new org.bukkit.event.Listener() {};
            getServer().getPluginManager().registerEvent(cls, holder, org.bukkit.event.EventPriority.MONITOR,
                    new org.bukkit.plugin.EventExecutor() {
                        @Override public void execute(org.bukkit.event.Listener l, org.bukkit.event.Event e) {
                            gate.run("event:elytraboost", () -> {
                                Object p = Reflect.invoke(getPlayer, e);
                                if (p instanceof org.bukkit.entity.Player && telemetry != null) {
                                    telemetry.event((org.bukkit.entity.Player) p, ac.thorium.mc.plugin.capture.EventFactory.boost("firework"));
                                }
                            });
                        }
                    }, this, true);
            paperBoostsAvailable = true;
        } catch (Throwable t) {
            getLogger().fine("Thorium: no PlayerElytraBoostEvent; firework boosts are read from right clicks");
        }
    }

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

    private boolean gatewayUsable() {
        boolean dev = !cfg.devSessionToken.isEmpty();
        String msg = SessionAuth.insecureGatewayMessage(cfg.gatewayUrl, dev);
        if (msg != null) getLogger().severe(msg);
        return SessionAuth.gatewayAllowed(cfg.gatewayUrl, dev);
    }

    private void startPipeline() {
        String version = getDescription().getVersion();
        SampleBuffer buffer = new SampleBuffer(400);
        settings = new ac.thorium.mc.plugin.config.NetworkSettings(cfg.sendIp);
        enforcer = new Enforcer(getServer(), sched, compat, gate, alerts, cfg, settings, getLogger());
        world = new WorldMirror();
        ConnectionConfig ccfg = new ConnectionConfig(cfg.gatewayUrl, cfg.devSessionToken, version);
        TokenSource tokens = new SessionAuth(cfg.gatewayUrl, cfg.serverToken, cfg.serverName, version, 10_000);
        connection = new EngineConnection(ccfg, tokens, () -> telemetry.buildHello(), new Handler(), getLogger());
        telemetry = new Telemetry(connection, buffer, world, compat, sched, gate, cfg, version, compat.cracked(getServer().getOnlineMode()), getLogger());
        if (entityTracker == null) entityTracker = new EntityTracker(cfg.entityRadius);
        if (packetEventsReady) {
            capture = new PacketCapture(telemetry, gate, entityTracker);
            PacketEvents.getAPI().getEventManager().registerListener(capture);
            outbound = new OutboundCapture(telemetry, entityTracker, gate);
            telemetry.setOutbound(outbound);
            PacketEvents.getAPI().getEventManager().registerListener(outbound);
            mitigator = new Mitigator(sched, compat, gate, cfg, () -> telemetry.tick(), getLogger());
            PacketEvents.getAPI().getEventManager().registerListener(mitigator);
            telemetry.enableTransactions(true);
        }
        events = new BukkitEvents(telemetry, capture, gate, settings);
        events.paperBoosts = paperBoostsAvailable;
        getServer().getPluginManager().registerEvents(events, this);
        activity = new ac.thorium.mc.plugin.capture.ActivityEvents(settings, () -> connection, telemetry, gate);
        getServer().getPluginManager().registerEvents(activity, this);
        activity.start(sched);
        worldEvents = new WorldBlockEvents(world, gate);
        getServer().getPluginManager().registerEvents(worldEvents, this);
        worldSampler = new WorldSampler(world, sched, gate, getServer(), this::engineReady);
        worldSampler.start();
        for (Player p : getServer().getOnlinePlayers()) telemetry.track(p);
        telemetry.start();
        tickTask = new TickTask(sched, compat, gate, telemetry, buffer);
        tickTask.start();
        connection.start();
    }

    private boolean engineReady() {
        EngineConnection c = connection;
        return c != null && c.state() == ConnectionState.READY;
    }

    private void stopPipeline() {
        if (tickTask != null) tickTask.stop();
        if (connection != null) connection.stop();
        if (worldSampler != null) worldSampler.stop();
        if (worldEvents != null) HandlerList.unregisterAll(worldEvents);
        if (telemetry != null) telemetry.stop();
        if (capture != null && packetEventsReady) PacketEvents.getAPI().getEventManager().unregisterListener(capture);
        if (outbound != null && packetEventsReady) PacketEvents.getAPI().getEventManager().unregisterListener(outbound);
        if (mitigator != null && packetEventsReady) PacketEvents.getAPI().getEventManager().unregisterListener(mitigator);
        if (events != null) HandlerList.unregisterAll(events);
        if (activity != null) { activity.stop(sched); HandlerList.unregisterAll(activity); }
        activity = null;
        connection = null; telemetry = null; capture = null; outbound = null; mitigator = null; tickTask = null; events = null;
        worldSampler = null; worldEvents = null; world = null;
    }

    public void reconnect() {
        reloadConfig();
        cfg = PluginConfig.from(getConfig());
        stopPipeline();
        if (!cfg.isConfigured()) { getLogger().severe("Thorium: still not configured."); return; }
        if (!gatewayUsable()) return;
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
            Telemetry t = telemetry;
            if (t != null && ack.hasPolicy()) t.applyPolicy(ack.getPolicy());
            if (t != null) t.snapshotAll();
            if (ack.hasConfig()) settings.apply(ack.getConfig());
        }
        @Override public void onConfig(ac.thorium.mc.proto.NetworkConfig c) { settings.apply(c); }
        @Override public void onVerdict(Verdict v) { enforcer.accept(v); }
        @Override public void onMitigate(Mitigate m) { if (mitigator != null) mitigator.accept(m); }
        @Override public void onPolicy(IngestPolicy p) { Telemetry t = telemetry; if (t != null) t.applyPolicy(p); }
        @Override public void onPluginUpdate(PluginUpdate u) { getLogger().warning("Thorium: plugin update available: " + u.getVersion() + " " + u.getDownloadUrl()); }
        @Override public void onGatewayControl(int opcode, byte[] payload) {
            if (opcode == FrameDiscriminator.GATEWAY_MESSAGE) getLogger().info("Thorium gateway: " + new String(payload, StandardCharsets.UTF_8));
            else if (opcode == FrameDiscriminator.GATEWAY_MOD_UPDATE) getLogger().warning("Thorium gateway: update notice");
        }
        @Override public void onStateChange(ConnectionState from, ConnectionState to) {
            Level lvl = (to == ConnectionState.HELD || to == ConnectionState.STOPPED) ? Level.INFO : Level.FINE;
            getLogger().log(lvl, "Thorium: connection " + from + " -> " + to);
        }
        @Override public void onDisconnected() { Telemetry t = telemetry; if (t != null) t.onDisconnected(); }
    }
}
