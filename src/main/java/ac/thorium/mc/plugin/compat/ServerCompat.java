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
    private final Method blockDataSend, getBlockData, legacyBlockSend, bulkBlockSend;
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
        Class<?> blockData = Reflect.loadClass("org.bukkit.block.data.BlockData");
        this.blockDataSend = blockData == null ? null : Reflect.method(Player.class, "sendBlockChange", org.bukkit.Location.class, blockData);
        this.getBlockData = blockData == null ? null : Reflect.method(org.bukkit.block.Block.class, "getBlockData");
        this.legacyBlockSend = Reflect.method(Player.class, "sendBlockChange", org.bukkit.Location.class, org.bukkit.Material.class, byte.class);
        this.bulkBlockSend = Reflect.method(Player.class, "sendBlockChanges", java.util.Collection.class);
    }

    public ServerSoftware software() { return software; }
    public String mcVersion() { return mcVersion; }

    public String proxyForwarding() {
        Boolean bungee = staticBool("org.spigotmc.SpigotConfig", "bungee");
        Boolean velocity = velocityEnabled();
        if (velocity != null && velocity.booleanValue()) return "velocity-modern";
        if (bungee != null && bungee.booleanValue()) return "bungeecord";
        if (bungee == null && velocity == null) return "unknown";
        return "none";
    }

    public static boolean cracked(boolean onlineMode, String forwarding) {
        if (onlineMode) return false;
        return "none".equals(forwarding) || "unknown".equals(forwarding);
    }

    public boolean cracked(boolean onlineMode) { return cracked(onlineMode, proxyForwarding()); }

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
                try { adventureKick.invoke(p, comp); return; } catch (Throwable ignored) {}
            }
        }
        p.kickPlayer(legacyMessage);
    }

    public void resendSection(Player p, int sx, int sy, int sz) {
        org.bukkit.World w = p.getWorld();
        int bx = sx << 4, by = sy << 4, bz = sz << 4;
        if (bulkBlockSend != null) {
            java.util.List<org.bukkit.block.BlockState> states = new java.util.ArrayList<>(4096);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) states.add(w.getBlockAt(bx + x, by + y, bz + z).getState());
            try { bulkBlockSend.invoke(p, states); return; } catch (Throwable ignored) {}
        }
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) sendBlockChange(p, w.getBlockAt(bx + x, by + y, bz + z));
    }

    private void sendBlockChange(Player p, org.bukkit.block.Block b) {
        if (blockDataSend != null && getBlockData != null) {
            Object data = Reflect.invoke(getBlockData, b);
            if (data != null) {
                try { blockDataSend.invoke(p, b.getLocation(), data); return; } catch (Throwable ignored) {}
            }
        }
        if (legacyBlockSend != null) {
            try { legacyBlockSend.invoke(p, b.getLocation(), b.getType(), b.getData()); } catch (Throwable ignored) {}
        }
    }

    public int ping(Player p) {
        try {
            return com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getPing(p);
        } catch (Throwable t) {
            return -1;
        }
    }
}
