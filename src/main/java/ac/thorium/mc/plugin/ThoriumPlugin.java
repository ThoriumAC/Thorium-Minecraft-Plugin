package ac.thorium.mc.plugin;

import ac.thorium.mc.plugin.capture.BukkitEvents;
import ac.thorium.mc.plugin.capture.PacketCapture;
import ac.thorium.mc.plugin.capture.PacketSend;
import ac.thorium.mc.plugin.command.ThoriumCommand;
import ac.thorium.mc.plugin.compat.ErrorGate;
import ac.thorium.mc.plugin.compat.Scheduler;
import ac.thorium.mc.plugin.compat.ServerCompat;
import ac.thorium.mc.plugin.config.PluginConfig;
import ac.thorium.mc.plugin.enforce.Enforcer;
import ac.thorium.mc.plugin.enforce.StaffAlerts;
import ac.thorium.mc.plugin.telemetry.ContextTracker;
import ac.thorium.mc.plugin.telemetry.SampleBuffer;
import ac.thorium.mc.plugin.telemetry.Telemetry;
import ac.thorium.mc.plugin.transport.*;
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
        telemetry = new Telemetry(connection, buffer, contexts, world, compat, sched, gate, cfg, version, getServer().getOnlineMode(), getLogger());
        if (packetEventsReady) {
            capture = new PacketCapture(telemetry, sched, gate);
            PacketEvents.getAPI().getEventManager().registerListener(capture);
            send = new PacketSend(telemetry, gate);
            PacketEvents.getAPI().getEventManager().registerListener(send);
            telemetry.enableTransactions(true);
        }
        events = new BukkitEvents(telemetry, capture, gate, cfg.sendIp);
        getServer().getPluginManager().registerEvents(events, this);
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
