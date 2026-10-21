package ac.thorium.mc.api;

import org.bukkit.entity.Player;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class ThoriumStats {
    public interface Recorder { void record(Player player, String kind, String key, double amount); }

    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();
    private static volatile Recorder recorder;

    private ThoriumStats() {}

    public static void bind(Recorder r) { recorder = r; }

    public static boolean validKey(String key) { return key != null && KEY.matcher(key).matches(); }

    public static void increment(Player player, String key, double amount) { emit(player, "custom", key, amount); }

    public static void max(Player player, String key, double value) { emit(player, "custom_max", key, value); }

    private static void emit(Player player, String kind, String key, double amount) {
        if (!validKey(key)) {
            if (warned.add(String.valueOf(key))) {
                Logger.getLogger("Thorium").warning("ThoriumStats: ignoring invalid stat key '" + key + "' (use a-z, 0-9, _; max 32)");
            }
            return;
        }
        Recorder r = recorder;
        if (r == null || player == null || Double.isNaN(amount) || Double.isInfinite(amount)) return;
        try {
            r.record(player, kind, key, amount);
        } catch (RuntimeException ignored) {
        }
    }
}
