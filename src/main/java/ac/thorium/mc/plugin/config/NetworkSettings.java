package ac.thorium.mc.plugin.config;

import ac.thorium.mc.proto.NetworkConfig;

public final class NetworkSettings {
    private volatile NetworkConfig current;

    public void apply(NetworkConfig c) { current = c; }

    public boolean sendIps() { NetworkConfig c = current; return c != null && c.getSendIps(); }

    public boolean advancedAnalytics() { NetworkConfig c = current; return c != null && c.getAdvancedAnalytics(); }

    public boolean reportsEnabled() { NetworkConfig c = current; return c != null && c.getReportsEnabled(); }

    public static final String DEFAULT_KICK_MESSAGE = "&cKicked by Thorium anti-cheat\n&7%reason%";

    public String kickMessage() { NetworkConfig c = current; return c == null || c.getKickMessage().isEmpty() ? DEFAULT_KICK_MESSAGE : c.getKickMessage(); }

    public String banMessage(String fallback) { NetworkConfig c = current; return c == null || c.getBanMessage().isEmpty() ? fallback : c.getBanMessage(); }
}
