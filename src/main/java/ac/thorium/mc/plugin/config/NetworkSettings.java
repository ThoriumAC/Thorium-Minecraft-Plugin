package ac.thorium.mc.plugin.config;

import ac.thorium.mc.proto.NetworkConfig;

/** Settings the network owns on the dashboard. Until the engine sends them, config.yml applies. */
public final class NetworkSettings {
    private final boolean localSendIp;
    private volatile NetworkConfig current;

    public NetworkSettings(boolean localSendIp) { this.localSendIp = localSendIp; }

    public void apply(NetworkConfig c) { current = c; }

    public boolean sendIps() { NetworkConfig c = current; return c == null ? localSendIp : c.getSendIps(); }

    public boolean advancedAnalytics() { NetworkConfig c = current; return c != null && c.getAdvancedAnalytics(); }

    public boolean reportsEnabled() { NetworkConfig c = current; return c != null && c.getReportsEnabled(); }

    public String kickMessage(String fallback) { NetworkConfig c = current; return c == null || c.getKickMessage().isEmpty() ? fallback : c.getKickMessage(); }

    public String banMessage(String fallback) { NetworkConfig c = current; return c == null || c.getBanMessage().isEmpty() ? fallback : c.getBanMessage(); }
}
