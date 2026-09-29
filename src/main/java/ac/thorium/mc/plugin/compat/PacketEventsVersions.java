package ac.thorium.mc.plugin.compat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PacketEventsVersions {
    private PacketEventsVersions() {}

    public static ServerVersion pinIfUnknown(String bukkitVersion) {
        ServerVersion latest = ServerVersion.getLatest();
        if (!isNewerThan(bukkitVersion, latest.getReleaseName())) return null;
        try {
            ServerManager sm = PacketEvents.getAPI().getServerManager();
            Field f = sm.getClass().getDeclaredField("serverVersion");
            f.setAccessible(true);
            if (f.get(sm) != null) return null;
            f.set(sm, latest);
            return latest;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isNewerThan(String bukkitVersion, String known) {
        int[] a = numbers(bukkitVersion), b = numbers(known);
        if (a.length == 0) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static final Pattern LEADING = Pattern.compile("\\d{1,9}(\\.\\d{1,9})*");

    private static int[] numbers(String version) {
        Matcher m = LEADING.matcher(version);
        return m.lookingAt() ? Arrays.stream(m.group().split("\\.")).mapToInt(Integer::parseInt).toArray() : new int[0];
    }
}
