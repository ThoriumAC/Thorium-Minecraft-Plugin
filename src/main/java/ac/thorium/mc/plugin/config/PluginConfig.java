package ac.thorium.mc.plugin.config;

import org.bukkit.configuration.ConfigurationSection;

public final class PluginConfig {
    public final String gatewayUrl;
    public final String serverToken;
    public final int flushIntervalMs;
    public final boolean enforce;
    public final String banCommand;
    public final boolean sendIp;
    public final String alertFormat;
    public final String warnFormat;
    public final String kickFormat;
    public final String devSessionToken;

    private PluginConfig(String gatewayUrl, String serverToken, int flushIntervalMs, boolean enforce, String banCommand,
                         boolean sendIp, String alertFormat, String warnFormat, String kickFormat, String devSessionToken) {
        this.gatewayUrl = gatewayUrl; this.serverToken = serverToken; this.flushIntervalMs = flushIntervalMs;
        this.enforce = enforce; this.banCommand = banCommand; this.sendIp = sendIp; this.alertFormat = alertFormat;
        this.warnFormat = warnFormat; this.kickFormat = kickFormat; this.devSessionToken = devSessionToken;
    }

    public static PluginConfig from(ConfigurationSection c) {
        String url = c.getString("gateway-url", "https://gateway.thorium.ac").trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        int flush = Math.max(25, Math.min(1000, c.getInt("flush-interval-ms", 75)));
        return new PluginConfig(
                url,
                c.getString("server-token", "").trim(),
                flush,
                c.getBoolean("enforce", true),
                c.getString("ban-command", "").trim(),
                c.getBoolean("send-ip", false),
                c.getString("alert-format", "&8[&cThorium&8] &f%player% &7failed &e%check% &7(VL %vl%, %confidence%%)"),
                c.getString("warn-format", "&c[Thorium] &fSuspicious activity detected (%check%). This is a warning."),
                c.getString("kick-format", "&cKicked by Thorium anti-cheat\n&7%reason%"),
                c.getString("dev.session-token", "").trim());
    }

    public boolean isConfigured() { return !serverToken.isEmpty() || !devSessionToken.isEmpty(); }
    public boolean useBanCommand() { return !banCommand.isEmpty(); }
}
