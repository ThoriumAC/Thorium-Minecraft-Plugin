package ac.thorium.mc.plugin.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigFile {
    private ConfigFile() {}

    public static void migrate(Path old, Path target, Logger log) {
        if (!Files.isRegularFile(old)) return;
        try {
            if (Files.exists(target)) {
                Files.delete(old);
                log.warning("Thorium: removed old " + old + "; " + target + " is the config now.");
            } else {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.move(old, target);
                log.info("Thorium: moved " + old + " to " + target + ".");
            }
            try { if (old.getParent() != null) Files.deleteIfExists(old.getParent()); } catch (DirectoryNotEmptyException ignored) {}
        } catch (IOException e) {
            log.warning("Thorium: could not migrate " + old + " to " + target + ": " + e.getMessage());
        }
    }

    public static void writeCredentials(Path file, String token, String serverName, boolean yaml) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        text = set(text, "server-token", yaml ? yamlQuote(token) : token, yaml);
        text = set(text, "server-name", yaml ? yamlQuote(serverName) : serverName.replace("\\", "\\\\"), yaml);
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static String set(String text, String key, String value, boolean yaml) {
        String line = key + (yaml ? ": " : "=") + value;
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*[:=].*$").matcher(text);
        if (m.find()) return text.substring(0, m.start()) + line + text.substring(m.end());
        return text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + line + "\n";
    }

    private static String yamlQuote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
