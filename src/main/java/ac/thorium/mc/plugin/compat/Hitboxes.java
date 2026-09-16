package ac.thorium.mc.plugin.compat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Vanilla entity hitbox sizes, for servers whose API will not state them.
 *
 * <p>{@code Entity#getWidth()} and {@code Entity#getHeight()} arrived in the 1.11
 * API. Before that the server knows every hitbox and simply has no way to say so,
 * and the engine's combat checks — Reach and HitAngle both measure to the closest
 * point of the target's box — sit out any attack whose target has no dimensions.
 * On a 1.8 server that is every attack on a mob: exactly the version where reach
 * cheating is most common, and exactly the checks that would catch it.
 *
 * <p>So the sizes are carried here instead. The table is the full set of vanilla
 * 1.8 entity types, which is a closed set that will never gain a member. An
 * entity not in it — a mod's, a plugin's, or one from a version newer than the
 * API that needed this table — returns 0 and the checks go on sitting it out.
 * Guessing a box for something unknown is the one move that could turn this into
 * a false ban: too small a box inflates the measured distance, and Reach bans on
 * distance.
 */
public final class Hitboxes {
    private Hitboxes() {}

    private static final class Box {
        final float w, h;
        Box(double w, double h) { this.w = (float) w; this.h = (float) h; }
    }

    private static final Map<String, Box> VANILLA_1_8 = new HashMap<String, Box>();

    private static void put(String type, double w, double h) { VANILLA_1_8.put(type, new Box(w, h)); }

    static {
        // Players and humanoids.
        put("PLAYER", 0.6, 1.8);
        put("ARMOR_STAND", 0.5, 1.975);
        put("VILLAGER", 0.6, 1.95);
        put("ZOMBIE", 0.6, 1.95);
        put("PIG_ZOMBIE", 0.6, 1.95);
        put("SKELETON", 0.6, 1.95);
        put("WITCH", 0.6, 1.95);
        put("BLAZE", 0.6, 1.8);
        put("CREEPER", 0.6, 1.7);
        put("ENDERMAN", 0.6, 2.9);
        put("SNOWMAN", 0.7, 1.9);
        put("IRON_GOLEM", 1.4, 2.9);
        put("GIANT", 3.6, 10.8);
        // Animals.
        put("PIG", 0.9, 0.9);
        put("SHEEP", 0.9, 1.3);
        put("COW", 0.9, 1.4);
        put("MUSHROOM_COW", 0.9, 1.4);
        put("CHICKEN", 0.4, 0.7);
        put("RABBIT", 0.6, 0.7);
        put("WOLF", 0.6, 0.85);
        put("OCELOT", 0.6, 0.7);
        put("HORSE", 1.4, 1.6);
        put("SQUID", 0.95, 0.95);
        put("BAT", 0.5, 0.9);
        // Hostiles with their own shapes.
        put("SPIDER", 1.4, 0.9);
        put("CAVE_SPIDER", 0.7, 0.5);
        put("SILVERFISH", 0.4, 0.3);
        put("ENDERMITE", 0.4, 0.3);
        put("GUARDIAN", 0.85, 0.85);
        put("GHAST", 4.0, 4.0);
        put("WITHER", 0.9, 3.5);
        put("ENDER_DRAGON", 16.0, 8.0);
        // Vehicles and objects a player can hit.
        put("MINECART", 0.98, 0.7);
        put("MINECART_CHEST", 0.98, 0.7);
        put("MINECART_FURNACE", 0.98, 0.7);
        put("MINECART_TNT", 0.98, 0.7);
        put("MINECART_HOPPER", 0.98, 0.7);
        put("MINECART_MOB_SPAWNER", 0.98, 0.7);
        put("MINECART_COMMAND", 0.98, 0.7);
        put("BOAT", 1.5, 0.6);
        put("ITEM_FRAME", 0.75, 0.75);
        put("PAINTING", 0.5, 0.5);
        put("DROPPED_ITEM", 0.25, 0.25);
        put("EXPERIENCE_ORB", 0.5, 0.5);
        put("PRIMED_TNT", 0.98, 0.98);
        put("FALLING_BLOCK", 0.98, 0.98);
        put("ENDER_CRYSTAL", 2.0, 2.0);
        // Projectiles, which a player can shoot down.
        put("ARROW", 0.5, 0.5);
        put("SNOWBALL", 0.25, 0.25);
        put("EGG", 0.25, 0.25);
        put("ENDER_PEARL", 0.25, 0.25);
        put("THROWN_EXP_BOTTLE", 0.25, 0.25);
        put("SPLASH_POTION", 0.25, 0.25);
        put("FIREBALL", 1.0, 1.0);
        put("SMALL_FIREBALL", 0.3125, 0.3125);
        put("WITHER_SKULL", 0.3125, 0.3125);
        put("FISHING_HOOK", 0.25, 0.25);
    }

    /**
     * Slimes and magma cubes are the one family whose box is not fixed per type:
     * vanilla scales it by the slime's size, which the 1.8 API does expose.
     */
    private static final double SLIME_PER_SIZE = 0.51000005;

    /**
     * The hitbox for a vanilla entity type, or null when this table does not
     * cover it. {@code size} is the slime size for slimes and magma cubes and is
     * ignored otherwise; pass 0 when the server will not say.
     *
     * @return {@code {width, height}} in blocks, or null
     */
    public static float[] vanilla(String entityType, int size) {
        if (entityType == null) return null;
        String key = entityType.toUpperCase(Locale.ROOT);
        if ("SLIME".equals(key) || "MAGMA_CUBE".equals(key)) {
            if (size <= 0) return null;
            float s = (float) (SLIME_PER_SIZE * size);
            return new float[] { s, s };
        }
        Box b = VANILLA_1_8.get(key);
        return b == null ? null : new float[] { b.w, b.h };
    }
}
