package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EntityMetadata;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Each viewer's entities as the client knows them, built from the spawn, move
 * and destroy packets it is sent. No Bukkit calls, so it can be read and
 * written on the viewer's netty thread.
 */
public final class EntityTracker {
    private static final Set<String> PROJECTILES = new HashSet<>(Arrays.asList(
            "arrow", "spectral_arrow", "trident", "fireball", "small_fireball", "wither_skull", "shulker_bullet",
            "firework_rocket", "wind_charge", "breeze_wind_charge", "dragon_fireball", "llama_spit", "snowball", "egg",
            "ender_pearl", "splash_potion", "lingering_potion", "potion", "tnt", "end_crystal"));

    public static final class Known {
        public final int id;
        public final String type;
        final boolean wanted;
        public volatile double x, y, z;
        public volatile float yaw, pitch;
        public volatile boolean onGround;
        public volatile EntityMetadata flags;
        volatile boolean inRange;

        Known(int id, String type, boolean wanted) { this.id = id; this.type = type; this.wanted = wanted; }

        void moveTo(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    private static final class View {
        final Map<Integer, Known> known = new ConcurrentHashMap<>();
        final Set<Integer> pinned = ConcurrentHashMap.newKeySet();
        volatile boolean hasPos;
        volatile double x, y, z;
    }

    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    private final int radius;

    public EntityTracker(int radius) { this.radius = radius; }

    private View view(UUID viewer) { return views.computeIfAbsent(viewer, k -> new View()); }

    public void viewerAt(UUID viewer, double x, double y, double z) {
        View v = view(viewer);
        v.x = x; v.y = y; v.z = z; v.hasPos = true;
    }

    /** Whether a point is within {@code r} blocks of the viewer on every axis; true while the viewer's position is unknown. */
    public boolean nearViewer(UUID viewer, double x, double y, double z, double r) {
        View v = views.get(viewer);
        return v == null || !v.hasPos || (Math.abs(x - v.x) <= r && Math.abs(y - v.y) <= r && Math.abs(z - v.z) <= r);
    }

    public Known spawn(UUID viewer, int id, String type, boolean wanted, double x, double y, double z, float yaw, float pitch) {
        Known k = new Known(id, type, wanted);
        k.moveTo(x, y, z); k.yaw = yaw; k.pitch = pitch;
        view(viewer).known.put(id, k);
        return k;
    }

    public Known get(UUID viewer, int id) {
        View v = views.get(viewer);
        return v == null ? null : v.known.get(id);
    }

    public void destroy(UUID viewer, int[] ids) {
        View v = views.get(viewer);
        if (v == null) return;
        for (int id : ids) { v.known.remove(id); v.pinned.remove(id); }
    }

    /** Whether the viewer's stream should carry packets about {@code id}. */
    public boolean contains(UUID viewer, int id) {
        View v = views.get(viewer);
        if (v == null) return false;
        if (v.pinned.contains(id)) return true;
        Known k = v.known.get(id);
        return k != null && inRange(v, k);
    }

    /** Calls {@code out} for each entity that came into range since the last call. */
    public void entered(UUID viewer, Consumer<Known> out) {
        View v = views.get(viewer);
        if (v == null) return;
        for (Known k : v.known.values()) {
            boolean in = v.pinned.contains(k.id) || inRange(v, k);
            if (in && !k.inRange) out.accept(k);
            k.inRange = in;
        }
    }

    /** Every entity the viewer knows, with range state reset so each is re-sent once it is in range. */
    public void restate(UUID viewer, Consumer<Known> out) {
        View v = views.get(viewer);
        if (v == null) return;
        for (Known k : v.known.values()) { k.inRange = false; out.accept(k); }
    }

    public void pin(UUID viewer, int id) { view(viewer).pinned.add(id); }

    public void forget(UUID viewer) { views.remove(viewer); }

    private boolean inRange(View v, Known k) {
        return k.wanted && v.hasPos && Math.abs(k.x - v.x) <= radius && Math.abs(k.y - v.y) <= radius && Math.abs(k.z - v.z) <= radius;
    }

    /** Living entities, vehicles and projectiles; {@code key} is the type's id without namespace. */
    static boolean wanted(String key, boolean livingOrMinecart) {
        return livingOrMinecart || key.endsWith("boat") || key.endsWith("raft") || PROJECTILES.contains(key);
    }
}
