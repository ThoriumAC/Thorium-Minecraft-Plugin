package ac.thorium.mc.plugin.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginConfigTest {
    private static PluginConfig load(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        return PluginConfig.from(y);
    }

    @Test
    void defaultsWhenKeysMissing() throws Exception {
        PluginConfig c = load("server-token: abc\n");
        assertEquals("https://gateway.thorium.ac", c.gatewayUrl);
        assertEquals(75, c.flushIntervalMs);
        assertTrue(c.enforce);
        assertFalse(c.useBanCommand());
        assertTrue(c.isConfigured());
        assertFalse(c.sendIp);
        assertEquals("", c.devSessionToken);
    }

    @Test
    void trailingSlashStrippedAndValuesRead() throws Exception {
        PluginConfig c = load("gateway-url: 'http://localhost:3100/'\nflush-interval-ms: 40\nenforce: false\nban-command: 'ban %player% %reason%'\nsend-ip: true\ndev:\n  session-token: dev\n");
        assertEquals("http://localhost:3100", c.gatewayUrl);
        assertEquals(40, c.flushIntervalMs);
        assertFalse(c.enforce);
        assertTrue(c.useBanCommand());
        assertTrue(c.sendIp);
        assertEquals("dev", c.devSessionToken);
        assertTrue(c.isConfigured());
    }

    @Test
    void flushIntervalClamped() throws Exception {
        assertEquals(25, load("flush-interval-ms: 1\n").flushIntervalMs);
        assertEquals(1000, load("flush-interval-ms: 5000\n").flushIntervalMs);
    }

    @Test
    void unconfiguredWithoutTokens() throws Exception {
        assertFalse(load("gateway-url: x\n").isConfigured());
    }
}
