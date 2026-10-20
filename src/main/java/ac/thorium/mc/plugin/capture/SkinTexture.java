package ac.thorium.mc.plugin.capture;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Mojang texture hash of the skin a player joined with, read from their game profile. */
public final class SkinTexture {
    private static final Pattern SKIN = Pattern.compile("\"SKIN\"\\s*:\\s*\\{(?:[^{}]|\\{[^{}]*\\})*?\"url\"\\s*:\\s*\"https?://textures\\.minecraft\\.net/texture/([0-9a-f]{32,64})\"");

    private SkinTexture() {}

    public static String hashFromProperty(String base64Value) {
        if (base64Value == null) return "";
        try {
            Matcher m = SKIN.matcher(new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    public static String of(Player p) {
        try {
            Object profile = p.getClass().getMethod("getProfile").invoke(p);
            Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
            Collection<?> textures = (Collection<?>) properties.getClass().getMethod("get", Object.class).invoke(properties, "textures");
            for (Object prop : textures) {
                Method value;
                try { value = prop.getClass().getMethod("getValue"); } catch (NoSuchMethodException e) { value = prop.getClass().getMethod("value"); }
                String hash = hashFromProperty((String) value.invoke(prop));
                if (!hash.isEmpty()) return hash;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
        return "";
    }
}
