package ac.thorium.mc.plugin.compat;

import ac.thorium.mc.proto.ServerSoftware;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerCompat {
    private static final Pattern MC_IN_VERSION = Pattern.compile("\\(MC: ([0-9]+(?:\\.[0-9]+){1,2})");
    private static final Pattern BUKKIT_VERSION = Pattern.compile("^([0-9]+(?:\\.[0-9]+){1,2})-R");
    private static final long TPS_WINDOW_NANOS = 5_000_000_000L;

    private final ServerSoftware software;
    private final String mcVersion;
    private final Method getTps, getAverageTickTime;
    private final Method adventureKick;
    private final Method legacyDeserialize;
    private final Object legacySerializer;
    private final long[] tickNanos = new long[256];
    private int tickCount;
    private volatile double fallbackTps = 20.0;

    public ServerCompat(Server server) {
        boolean folia = Reflect.classPresent("io.papermc.paper.threadedregions.RegionizedServer");
        boolean paper = Reflect.classPresent("com.destroystokyo.paper.PaperConfig") || Reflect.classPresent("io.papermc.paper.configuration.Configuration");
        boolean spigot = Reflect.classPresent("org.spigotmc.SpigotConfig");
        boolean bukkit = Reflect.classPresent("org.bukkit.Bukkit");
        this.software = detectSoftware(folia, paper, spigot, bukkit);
        this.mcVersion = parseMcVersion(server.getBukkitVersion(), server.getVersion());
        this.getTps = Reflect.method(Bukkit.class, "getTPS");
        this.getAverageTickTime = Reflect.method(Bukkit.class, "getAverageTickTime");
        // Never spell "net.kyori…" as a string literal: shadow relocates string constants too, and the
        // relocated name would not exist at runtime. Discover Paper's Adventure classes structurally instead.
        Method kick = null;
        for (Method m : Player.class.getMethods()) {
            if (m.getName().equals("kick") && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].getName().endsWith(".text.Component")) { kick = m; break; }
        }
        this.adventureKick = kick;
        Object serializer = null; Method deserialize = null;
        if (kick != null) {
            String serializerName = kick.getParameterTypes()[0].getName().replace(".text.Component", ".text.serializer.legacy.LegacyComponentSerializer");
            Method legacySection = Reflect.method(serializerName, "legacySection");
            serializer = Reflect.invoke(legacySection, null);
            if (serializer != null) deserialize = Reflect.method(legacySection.getReturnType(), "deserialize", String.class);
        }
        this.legacySerializer = serializer;
        this.legacyDeserialize = deserialize;
    }

    public ServerSoftware software() { return software; }
    public String mcVersion() { return mcVersion; }

    /**
     * How this server receives player identity: "none", "bungeecord" (the legacy
     * proxy handshake, which Velocity can also speak), "velocity-modern", or
     * "unknown" when neither switch can be read.
     *
     * <p>Worth reporting because a backend behind any proxy runs with
     * online-mode off while its players still carry real Mojang UUIDs. Anything
     * that reads online-mode as "these accounts are unauthenticated" is wrong on
     * every proxied network, which is most of them.
     */
    public String proxyForwarding() {
        Boolean bungee = staticBool("org.spigotmc.SpigotConfig", "bungee");
        Boolean velocity = velocityEnabled();
        if (velocity != null && velocity.booleanValue()) return "velocity-modern";
        if (bungee != null && bungee.booleanValue()) return "bungeecord";
        if (bungee == null && velocity == null) return "unknown";
        return "none";
    }

    /**
     * Whether this server's players should be reported as unauthenticated.
     *
     * <p>Not simply the inverse of online-mode. A backend behind Velocity or
     * BungeeCord runs with online-mode off by design — the proxy did the
     * authentication and forwards the result — while the UUIDs it receives are
     * real Mojang UUIDs. Reading online-mode alone marks every player on every
     * proxied network as cracked, which is most networks.
     *
     * <p>When neither forwarding switch can be read we are on something that has
     * no proxy support to speak of, and online-mode alone is the honest answer.
     */
    public static boolean cracked(boolean onlineMode, String forwarding) {
        if (onlineMode) return false;
        return "none".equals(forwarding) || "unknown".equals(forwarding);
    }

    /** {@link #cracked(boolean, String)} for this server. */
    public boolean cracked(boolean onlineMode) { return cracked(onlineMode, proxyForwarding()); }

    /**
     * Paper's Velocity switch, which has lived in two places: a static field on
     * the old PaperConfig, and a nested record reached through
     * GlobalConfiguration.get() since the 1.19 config rewrite.
     */
    private static Boolean velocityEnabled() {
        Boolean old = staticBool("com.destroystokyo.paper.PaperConfig", "velocitySupport");
        if (old != null) return old;
        try {
            Class<?> global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            Object cfg = global.getMethod("get").invoke(null);
            if (cfg == null) return null;
            Object proxies = cfg.getClass().getField("proxies").get(cfg);
            Object vel = proxies.getClass().getField("velocity").get(proxies);
            Object enabled = vel.getClass().getField("enabled").get(vel);
            return enabled instanceof Boolean ? (Boolean) enabled : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A public static boolean field, or null when the class or field is absent. */
    private static Boolean staticBool(String className, String field) {
        try {
            Object v = Class.forName(className).getField(field).get(null);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static ServerSoftware detectSoftware(boolean folia, boolean paper, boolean spigot, boolean bukkit) {
        if (folia) return ServerSoftware.SERVER_SOFTWARE_FOLIA;
        if (paper) return ServerSoftware.SERVER_SOFTWARE_PAPER;
        if (spigot) return ServerSoftware.SERVER_SOFTWARE_SPIGOT;
        if (bukkit) return ServerSoftware.SERVER_SOFTWARE_BUKKIT;
        return ServerSoftware.SERVER_SOFTWARE_OTHER;
    }

    public static String parseMcVersion(String bukkitVersion, String serverVersion) {
        Matcher m = BUKKIT_VERSION.matcher(bukkitVersion == null ? "" : bukkitVersion);
        if (m.find()) return m.group(1);
        m = MC_IN_VERSION.matcher(serverVersion == null ? "" : serverVersion);
        if (m.find()) return m.group(1);
        return "unknown";
    }

    public synchronized void onTick(long nowNanos) {
        tickNanos[tickCount % tickNanos.length] = nowNanos;
        tickCount++;
        int n = Math.min(tickCount, tickNanos.length);
        long[] ordered = new long[n];
        for (int i = 0; i < n; i++) ordered[i] = tickNanos[(tickCount - n + i) % tickNanos.length];
        fallbackTps = tpsFromTicks(ordered, n, nowNanos);
    }

    /** ticks[0..count) ascending tick timestamps; TPS over the last 5 s window; 20 when fewer than 2 samples. */
    public static double tpsFromTicks(long[] ticks, int count, long nowNanos) {
        if (count < 2) return 20.0;
        int first = 0;
        while (first < count - 1 && nowNanos - ticks[first] > TPS_WINDOW_NANOS) first++;
        long span = ticks[count - 1] - ticks[first];
        int n = count - 1 - first;
        if (n < 1 || span <= 0) return 20.0;
        return Math.max(0.0, Math.min(20.0, n * 1_000_000_000.0 / span));
    }

    public double tps() {
        Object v = Reflect.invoke(getTps, null);
        if (v instanceof double[] && ((double[]) v).length > 0) return Math.max(0.0, Math.min(20.0, ((double[]) v)[0]));
        return fallbackTps;
    }

    public double mspt() {
        Object v = Reflect.invoke(getAverageTickTime, null);
        return v instanceof Double ? (Double) v : 0.0;
    }

    public void kick(Player p, String legacyMessage) {
        if (adventureKick != null && legacyDeserialize != null) {
            Object comp = Reflect.invoke(legacyDeserialize, legacySerializer, legacyMessage);
            if (comp != null) {
                try { adventureKick.invoke(p, comp); return; } catch (Throwable ignored) { /* fall through to legacy */ }
            }
        }
        p.kickPlayer(legacyMessage);
    }

    public int ping(Player p) {
        try {
            return com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getPing(p);
        } catch (Throwable t) {
            return -1;
        }
    }
}
