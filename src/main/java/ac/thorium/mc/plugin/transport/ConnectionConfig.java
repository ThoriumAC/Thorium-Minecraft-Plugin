package ac.thorium.mc.plugin.transport;

public final class ConnectionConfig {
    public final String gatewayUrl, devSessionToken, pluginVersion;
    public long backoffInitialMs = 2000, backoffMaxMs = 60000, outdatedHoldMs = 3_600_000L;
    public int connectTimeoutMs = 10000, helloAckTimeoutMs = 10000;

    public ConnectionConfig(String gatewayUrl, String devSessionToken, String pluginVersion) {
        this.gatewayUrl = gatewayUrl; this.devSessionToken = devSessionToken == null ? "" : devSessionToken; this.pluginVersion = pluginVersion;
    }

    public ConnectionConfig timings(long backoffInitialMs, long backoffMaxMs, int connectTimeoutMs, int helloAckTimeoutMs, long outdatedHoldMs) {
        this.backoffInitialMs = backoffInitialMs; this.backoffMaxMs = backoffMaxMs; this.connectTimeoutMs = connectTimeoutMs;
        this.helloAckTimeoutMs = helloAckTimeoutMs; this.outdatedHoldMs = outdatedHoldMs;
        return this;
    }

    public String webSocketUrl() { return SessionAuth.toWebSocketUrl(gatewayUrl); }
}
