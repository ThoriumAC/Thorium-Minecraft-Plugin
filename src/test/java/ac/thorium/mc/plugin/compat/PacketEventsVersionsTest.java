package ac.thorium.mc.plugin.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEventsVersionsTest {
    @Test
    void newerBuildStringWins() {
        assertTrue(PacketEventsVersions.isNewerThan("26.3.build.28-alpha", "26.2"));
        assertTrue(PacketEventsVersions.isNewerThan("27.1.build.2", "26.2"));
        assertTrue(PacketEventsVersions.isNewerThan("26.10.build.1", "26.2"));
    }

    @Test
    void knownOrOlderVersionsAreLeftAlone() {
        assertFalse(PacketEventsVersions.isNewerThan("26.2.build.9-beta", "26.2"));
        assertFalse(PacketEventsVersions.isNewerThan("26.1.build.4", "26.2"));
        assertFalse(PacketEventsVersions.isNewerThan("1.21.4-R0.1-SNAPSHOT", "26.2"));
        assertFalse(PacketEventsVersions.isNewerThan("1.8.8-R0.1-SNAPSHOT", "1.8.8"));
        assertFalse(PacketEventsVersions.isNewerThan("1.21.12-R0.1-SNAPSHOT", "1.21.12"));
    }

    @Test
    void suffixedPatchVersionIsNotTruncated() {
        assertTrue(PacketEventsVersions.isNewerThan("1.21.12-R0.1-SNAPSHOT", "1.21.4"));
    }

    @Test
    void unreadableVersionsAreLeftAlone() {
        assertFalse(PacketEventsVersions.isNewerThan("Unknown", "26.2"));
        assertFalse(PacketEventsVersions.isNewerThan("", "26.2"));
    }
}
