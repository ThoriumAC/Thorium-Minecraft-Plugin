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
        // A backend behind a proxy runs online-mode off while its players keep
        // real UUIDs. Reporting either half alone invites the wrong conclusion.
        String proxied = joined(ThoriumCommand.compatLines("0.2.0", "PAPER", "1.21.11", false, "21",
                false, "velocity-modern", true, 4, Collections.<String>emptyList()));
        assertTrue(proxied.contains("online-mode off, forwarding velocity-modern"), proxied);
        // The whole point of the line: off + forwarding is not cracked.
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
        // Zero sections with streaming off is the engine not asking for terrain.
        // Zero sections with streaming on is a bug. The wording has to separate
        // them, because that is the call the compat harness makes from this line.
        String off = joined(ThoriumCommand.compatLines("0.2.0", "PAPER", "1.21.11", false, "21",
                true, "none", false, 0, Collections.<String>emptyList()));
        assertTrue(off.contains("world: §foff"), off);
        assertFalse(off.contains("sections held"), off);
    }

    @Test
    void branchesDescribeThisRuntime() {
        // Runs against whatever Bukkit the tests compile against, so it asserts
        // the shape rather than the branch: every lookup must report something.
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
