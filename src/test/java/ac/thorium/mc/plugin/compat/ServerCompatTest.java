package ac.thorium.mc.plugin.compat;

import ac.thorium.mc.proto.ServerSoftware;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCompatTest {
    @Test
    void parsesMcVersionFromBukkitVersion() {
        assertEquals("1.21.4", ServerCompat.parseMcVersion("1.21.4-R0.1-SNAPSHOT", "git-Paper-123 (MC: 1.21.4)"));
        assertEquals("1.8.8", ServerCompat.parseMcVersion("1.8.8-R0.1-SNAPSHOT", "git-Spigot-x (MC: 1.8.8)"));
        assertEquals("1.20", ServerCompat.parseMcVersion("garbage", "git-Paper-1 (MC: 1.20)"));
        assertEquals("unknown", ServerCompat.parseMcVersion("garbage", "garbage"));
    }

    @Test
    void detectsSoftwareByPrecedence() {
        assertEquals(ServerSoftware.SERVER_SOFTWARE_FOLIA, ServerCompat.detectSoftware(true, true, true, true));
        assertEquals(ServerSoftware.SERVER_SOFTWARE_PAPER, ServerCompat.detectSoftware(false, true, true, true));
        assertEquals(ServerSoftware.SERVER_SOFTWARE_SPIGOT, ServerCompat.detectSoftware(false, false, true, true));
        assertEquals(ServerSoftware.SERVER_SOFTWARE_BUKKIT, ServerCompat.detectSoftware(false, false, false, true));
        assertEquals(ServerSoftware.SERVER_SOFTWARE_OTHER, ServerCompat.detectSoftware(false, false, false, false));
    }

    @Test
    void tpsFromTickTimestamps() {
        long[] ticks = new long[200];
        for (int i = 0; i < 100; i++) ticks[i] = i * 50_000_000L;
        assertEquals(20.0, ServerCompat.tpsFromTicks(ticks, 100, 99 * 50_000_000L), 0.01);
        for (int i = 0; i < 50; i++) ticks[i] = i * 100_000_000L;
        assertEquals(10.0, ServerCompat.tpsFromTicks(ticks, 50, 49 * 100_000_000L), 0.01);
        assertEquals(20.0, ServerCompat.tpsFromTicks(ticks, 1, 0), 0.01);
    }

    @Test
    void proxiedPlayersAreNotCracked() {
        // Behind Velocity or BungeeCord: online-mode off, but UUIDs are real.
        assertFalse(ServerCompat.cracked(false, "velocity-modern"));
        assertFalse(ServerCompat.cracked(false, "bungeecord"));
    }

    @Test
    void offlineWithoutAProxyIsCracked() {
        assertTrue(ServerCompat.cracked(false, "none"));
    }

    @Test
    void onlineModeSettlesItWhateverTheForwarding() {
        for (String f : new String[] {"none", "bungeecord", "velocity-modern", "unknown"}) {
            assertFalse(ServerCompat.cracked(true, f), f);
        }
    }

    @Test
    void unreadableForwardingFallsBackToOnlineMode() {
        // No proxy support to read: online-mode alone decides.
        assertTrue(ServerCompat.cracked(false, "unknown"));
    }
}
