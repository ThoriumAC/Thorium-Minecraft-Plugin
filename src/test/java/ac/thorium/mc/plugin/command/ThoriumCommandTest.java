package ac.thorium.mc.plugin.command;

import ac.thorium.mc.plugin.world.SnapshotReader;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThoriumCommandTest {
    private static String joined(List<String> lines) { return String.join("\n", lines); }

    @Test
    void compatReportNamesTheResolvedBranches() {
        String out = joined(ThoriumCommand.compatLines("0.2.0", "SPIGOT", "1.8.8", false, "1.8.0_392",
                true, "none", true, 128, Arrays.asList("block-states: legacy MATERIAL:data", "biome: getBiome(x,z)")));
        assertTrue(out.contains("SPIGOT 1.8.8"), out);
        assertTrue(out.contains("Java §f1.8.0_392"), out);
        assertTrue(out.contains("128 sections held"), out);
        assertTrue(out.contains("block-states: legacy MATERIAL:data"), out);
        assertTrue(out.contains("biome: getBiome(x,z)"), out);
    }

    @Test
    void identityLineCarriesBothHalves() {
        // Behind a proxy: online-mode off, real UUIDs.
        String proxied = joined(ThoriumCommand.compatLines("0.2.0", "PAPER", "1.21.11", false, "21",
                false, "velocity-modern", true, 4, Collections.<String>emptyList()));
        assertTrue(proxied.contains("online-mode off, forwarding velocity-modern"), proxied);
        assertTrue(proxied.contains("players authenticated"), proxied);

        String direct = joined(ThoriumCommand.compatLines("0.2.0", "PAPER", "1.21.11", false, "21",
                true, "none", true, 4, Collections.<String>emptyList()));
        assertTrue(direct.contains("online-mode on, forwarding none"), direct);
        assertTrue(direct.contains("players authenticated"), direct);

        String offline = joined(ThoriumCommand.compatLines("0.2.0", "PAPER", "1.21.11", false, "21",
                false, "none", true, 4, Collections.<String>emptyList()));
        assertTrue(offline.contains("players cracked"), offline);
    }

    @Test
    void streamingOffIsNotReportedAsBroken() {
        // Zero sections must read differently with streaming off and on.
        String off = joined(ThoriumCommand.compatLines("0.2.0", "PAPER", "1.21.11", false, "21",
                true, "none", false, 0, Collections.<String>emptyList()));
        assertTrue(off.contains("world: §foff"), off);
        assertFalse(off.contains("sections held"), off);
    }

    @Test
    void branchesDescribeThisRuntime() {
        // Depends on the Bukkit on the test classpath, so only the shape is checked.
        List<String> branches = SnapshotReader.branches();
        assertFalse(branches.isEmpty());
        for (String b : branches) {
            assertTrue(b.contains(": "), b);
            assertFalse(b.endsWith(": "), b);
        }
        assertTrue(joined(branches).contains("biome:"));
    }

    @Test
    void compatIsOfferedToAdminsOnly() {
        assertTrue(ThoriumCommand.complete("comp", true, true).contains("compat"));
        assertFalse(ThoriumCommand.complete("comp", false, true).contains("compat"));
        assertEquals(Arrays.asList("capture", "compat"), ThoriumCommand.complete("c", true, false));
    }
}
