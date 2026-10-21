package ac.thorium.mc.plugin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileTest {
    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void setReplacesInPlaceAndKeepsComments() {
        String yml = "# token\nserver-token: \"\"\n# name\nserver-name: \"\"\nenforce: true\n";
        String out = ConfigFile.set(ConfigFile.set(yml, "server-token", "\"org_a\"", true), "server-name", "\"Lobby\"", true);
        assertEquals("# token\nserver-token: \"org_a\"\n# name\nserver-name: \"Lobby\"\nenforce: true\n", out);
        assertEquals("a=1\nserver-token=org_b\n", ConfigFile.set("a=1", "server-token", "org_b", false));
        assertEquals("server-token=x\r\n", ConfigFile.set("server-token=\r\n", "server-token", "x", false));
    }

    @Test
    void writeCredentialsQuotesYaml(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("thorium.yml");
        Files.write(f, "server-token: \"\"\nserver-name: \"\"\n".getBytes(StandardCharsets.UTF_8));
        ConfigFile.writeCredentials(f, "org_x", "Bob's \"SMP\"", true);
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f.toFile());
        assertEquals("org_x", y.getString("server-token"));
        assertEquals("Bob's \"SMP\"", y.getString("server-name"));
    }

    @Test
    void migrateMovesThenDeletesOld(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("plugins/Thorium/config.yml"), neu = dir.resolve("config/thorium.yml");
        Files.createDirectories(old.getParent());
        Files.write(old, "a".getBytes(StandardCharsets.UTF_8));
        ConfigFile.migrate(old, neu, LOG);
        assertEquals("a", new String(Files.readAllBytes(neu), StandardCharsets.UTF_8));
        assertFalse(Files.exists(old.getParent()));

        Files.createDirectories(old.getParent());
        Files.write(old, "b".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(old.getParent().resolve("captures"));
        ConfigFile.migrate(old, neu, LOG);
        assertEquals("a", new String(Files.readAllBytes(neu), StandardCharsets.UTF_8));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(old.getParent()));
    }
}
