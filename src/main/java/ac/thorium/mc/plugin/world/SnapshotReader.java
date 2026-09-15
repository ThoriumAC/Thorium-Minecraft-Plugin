package ac.thorium.mc.plugin.world;

import ac.thorium.mc.plugin.compat.Reflect;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Reads block state strings out of a {@link ChunkSnapshot} across every server
 * version the plugin supports.
 *
 * <p>{@code ChunkSnapshot.getBlockData(int, int, int)} returned an {@code int}
 * data value up to 1.12 and a {@code BlockData} from 1.13 on — same name, same
 * parameters, different return type — so the call has to be bound at runtime.
 * Modern servers get the full state string
 * ({@code minecraft:oak_stairs[facing=north,...]}); older ones get the legacy
 * {@code MATERIAL:data} form. Either way the engine treats it as an opaque
 * palette key.
 *
 * <p>A snapshot is an in-memory copy, so everything here is safe (and meant) to
 * run on a worker thread. Only taking the snapshot needs the owning thread.
 *
 * <p>This is the hot path: at a high column budget it runs hundreds of thousands
 * of times a second, and its allocation rate — not its thread — is what shows up
 * as main-thread GC pauses. Hence the {@link MethodHandle} (a reflective
 * {@code invoke} allocates a varargs array and boxes three ints <em>per block</em>),
 * the identity-keyed palette, and the empty-section fast path.
 */
public final class SnapshotReader {
    private static final Method SNAP_BLOCK_DATA =
            Reflect.method(ChunkSnapshot.class, "getBlockData", int.class, int.class, int.class);
    private static final Method SNAP_BLOCK_TYPE_ID =
            Reflect.method(ChunkSnapshot.class, "getBlockTypeId", int.class, int.class, int.class);
    private static final Method SNAP_SECTION_EMPTY = Reflect.method(ChunkSnapshot.class, "isSectionEmpty", int.class);
    private static final Method BLOCK_DATA_AS_STRING =
            Reflect.method("org.bukkit.block.data.BlockData", "getAsString");
    private static final Method MATERIAL_BY_ID = Reflect.method(Material.class, "getMaterial", int.class);
    private static final Method WORLD_MIN_HEIGHT = Reflect.method(World.class, "getMinHeight");
    private static final Method BLOCK_GET_BLOCK_DATA = Reflect.method(Block.class, "getBlockData");
    // Biomes moved twice: getBiome(x, z) on 1.8, getBiome(x, y, z) once biomes
    // became three-dimensional in 1.15, and the return went from an enum to a
    // registry-keyed object in 1.19. Name resolution below handles all three.
    private static final Method SNAP_BIOME_XYZ =
            Reflect.method(ChunkSnapshot.class, "getBiome", int.class, int.class, int.class);
    private static final Method SNAP_BIOME_XZ =
            Reflect.method(ChunkSnapshot.class, "getBiome", int.class, int.class);
    private static final Method BIOME_GET_KEY = Reflect.method("org.bukkit.block.Biome", "getKey");

    /** True on 1.13+, where getBlockData yields a BlockData rather than a data value. */
    private static final boolean MODERN =
            SNAP_BLOCK_DATA != null && !int.class.equals(SNAP_BLOCK_DATA.getReturnType()) && BLOCK_DATA_AS_STRING != null;

    public static final String AIR = MODERN ? "minecraft:air" : "AIR:0";

    /** (ChunkSnapshot, int, int, int) -> Object, bound once; no boxing, no varargs array. */
    private static final MethodHandle BLOCK_AT = handle(SNAP_BLOCK_DATA);
    private static final MethodHandle TYPE_ID_AT = handle(SNAP_BLOCK_TYPE_ID);
    /** (Object) -> Object for BlockData.getAsString. */
    private static final MethodHandle AS_STRING = handle1(BLOCK_DATA_AS_STRING);

    private static MethodHandle handle(Method m) {
        if (m == null) return null;
        try {
            return MethodHandles.lookup().unreflect(m)
                    .asType(MethodType.methodType(Object.class, ChunkSnapshot.class, int.class, int.class, int.class));
        } catch (Throwable t) {
            return null;
        }
    }

    private static MethodHandle handle1(Method m) {
        if (m == null) return null;
        try {
            return MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(Object.class, Object.class));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether {@code isSectionEmpty} has been proven trustworthy on this server.
     * 0 = not yet calibrated, 1 = trusted, -1 = rejected. A false "empty" would
     * report air where there is stone and feed that to a ban decision, so the
     * index convention is verified against real reads rather than assumed.
     */
    private static volatile int emptyFastPath;

    private SnapshotReader() {}

    public static boolean modern() { return MODERN; }

    /**
     * The biome name at a section-local column, e.g. "minecraft:plains", or "" when
     * the server will not say.
     *
     * Only called once per section rather than per block: the viewer tints grass
     * and water with it, and one name for a 16-block cube is close enough for
     * that while costing a short string instead of 4096 of them.
     */
    public static String biomeAt(ChunkSnapshot snap, int x, int y, int z) {
        Object biome = null;
        try {
            if (SNAP_BIOME_XYZ != null) {
                biome = SNAP_BIOME_XYZ.invoke(snap, x, y, z);
            } else if (SNAP_BIOME_XZ != null) {
                biome = SNAP_BIOME_XZ.invoke(snap, x, z);
            }
        } catch (Throwable t) {
            return "";
        }
        return biomeName(biome);
    }

    /** NamespacedKey when the server has one, else the enum name lower-cased. */
    static String biomeName(Object biome) {
        if (biome == null) return "";
        if (BIOME_GET_KEY != null) {
            try {
                Object key = BIOME_GET_KEY.invoke(biome);
                if (key != null) return key.toString();
            } catch (Throwable ignored) {
                // Fall through to the enum name.
            }
        }
        String name = biome.toString();
        return name.isEmpty() ? "" : "minecraft:" + name.toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean emptyFastPathEnabled() { return emptyFastPath == 1; }

    /**
     * Which reflection branch each version-adaptive lookup resolved to.
     *
     * <p>Every one of these fails silently. A null handle does not throw; it
     * makes the mirror return air, or the biome an empty string, and the engine
     * then judges a player standing on ground it believes is not there. On a
     * server version nobody has run before, this is the difference between
     * "world streaming is off" and "world streaming is broken here".
     */
    public static java.util.List<String> branches() {
        java.util.List<String> l = new ArrayList<String>();
        l.add("block-states: " + (MODERN ? "1.13+ BlockData.getAsString"
                : SNAP_BLOCK_TYPE_ID != null ? "legacy MATERIAL:data"
                : "NONE — the mirror cannot read blocks on this server"));
        l.add("block-handle: " + (BLOCK_AT != null ? "MethodHandle" : TYPE_ID_AT != null ? "MethodHandle (legacy ids)" : "none"));
        l.add("biome: " + (SNAP_BIOME_XYZ != null ? "getBiome(x,y,z)"
                : SNAP_BIOME_XZ != null ? "getBiome(x,z)"
                : "unavailable — sections stream without a biome"));
        l.add("biome-name: " + (BIOME_GET_KEY != null ? "NamespacedKey.getKey()" : "enum name"));
        l.add("min-height: " + (WORLD_MIN_HEIGHT != null ? "World.getMinHeight()" : "assumed 0 (pre-1.18)"));
        l.add("empty-section: " + (SNAP_SECTION_EMPTY == null ? "absent"
                : emptyFastPath == 1 ? "trusted" : emptyFastPath == -1 ? "rejected" : "not yet calibrated"));
        l.add("live-block: " + (BLOCK_GET_BLOCK_DATA != null ? "Block.getBlockData()" : "legacy id+data"));
        return l;
    }

    /** True when nothing can be read out of a snapshot at all — the mirror would stream air. */
    public static boolean readable() { return BLOCK_AT != null || TYPE_ID_AT != null; }

    /** Lowest block y in the world: -64 on 1.18+, 0 before {@code getMinHeight} existed. */
    public static int minHeight(World w) {
        Object v = Reflect.invoke(WORLD_MIN_HEIGHT, w);
        return v instanceof Integer ? ((Integer) v).intValue() : 0;
    }

    /** The section y range covering a world, as {@code [min, max]} section coordinates. */
    public static int minSectionY(World w) { return minHeight(w) >> 4; }

    public static int maxSectionY(World w) { return (w.getMaxHeight() - 1) >> 4; }

    /**
     * Proves or rejects the empty-section fast path using one real snapshot.
     *
     * <p>Bukkit's {@code isSectionEmpty(int)} is documented as taking a "section Y
     * coordinate", which has meant an array index on every version that matters
     * ({@code sectionY - minSectionY}) — but a wrong reading here silently turns
     * solid ground into air. So every section it claims is empty is read in full
     * once; a single non-air block disqualifies the optimisation permanently.
     */
    static synchronized void calibrate(ChunkSnapshot snap, int minSy, int maxSy) {
        if (emptyFastPath != 0 || SNAP_SECTION_EMPTY == null) {
            if (SNAP_SECTION_EMPTY == null) emptyFastPath = -1;
            return;
        }
        int claimedEmpty = 0;
        for (int sy = minSy; sy <= maxSy; sy++) {
            Object v = Reflect.invoke(SNAP_SECTION_EMPTY, snap, Integer.valueOf(sy - minSy));
            if (!(v instanceof Boolean)) { emptyFastPath = -1; return; }
            if (!((Boolean) v).booleanValue()) continue;
            claimedEmpty++;
            int baseY = sy << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!AIR.equals(readOne(snap, x, baseY + y, z))) {
                            emptyFastPath = -1;   // the index convention is not what we assumed
                            return;
                        }
                    }
                }
            }
        }
        // Only trust it once it has actually been exercised.
        emptyFastPath = claimedEmpty > 0 ? 1 : 0;
    }

    private static boolean isEmpty(ChunkSnapshot snap, int sectionY, int minSy) {
        if (emptyFastPath != 1) return false;
        Object v = Reflect.invoke(SNAP_SECTION_EMPTY, snap, Integer.valueOf(sectionY - minSy));
        return v instanceof Boolean && ((Boolean) v).booleanValue();
    }

    /**
     * Decodes one section straight into its palette form.
     *
     * <p>One pass, no intermediate {@code String[4096]}: block states are keyed by
     * identity, because a palette-backed snapshot hands back the same instance for
     * every block sharing a state, so {@code getAsString} runs once per distinct
     * state instead of once per block.
     *
     * @param sectionY section coordinate (world y >> 4), which may be negative
     * @return the encoded section, or null if it lies outside the snapshot
     */
    public static SectionData readSection(ChunkSnapshot snap, int sectionY, int minSectionY, int maxSectionY) {
        if (snap == null || sectionY < minSectionY || sectionY > maxSectionY) return null;
        if (isEmpty(snap, sectionY, minSectionY)) return SectionData.uniform(AIR);

        int baseY = sectionY << 4;
        List<String> palette = new ArrayList<String>(8);
        int[] ids = new int[SectionData.BLOCKS];
        if (MODERN && BLOCK_AT != null && AS_STRING != null) {
            IdentityHashMap<Object, Integer> seen = new IdentityHashMap<Object, Integer>(16);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Object bd;
                        try {
                            bd = (Object) BLOCK_AT.invokeExact(snap, x, baseY + y, z);
                        } catch (Throwable t) {
                            bd = null;
                        }
                        Integer id = bd == null ? null : seen.get(bd);
                        if (id == null) {
                            String s = bd == null ? AIR : asString(bd);
                            id = Integer.valueOf(indexOf(palette, s));
                            if (bd != null) seen.put(bd, id);
                        }
                        ids[SectionData.offset(x, y, z)] = id.intValue();
                    }
                }
            }
        } else {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        ids[SectionData.offset(x, y, z)] = indexOf(palette, readOne(snap, x, baseY + y, z));
                    }
                }
            }
        }
        return SectionData.of(palette, ids);
    }

    /** Palettes are tiny (a handful of entries); a linear scan beats hashing strings. */
    private static int indexOf(List<String> palette, String s) {
        for (int i = 0; i < palette.size(); i++) {
            if (palette.get(i).equals(s)) return i;
        }
        palette.add(s);
        return palette.size() - 1;
    }

    private static String asString(Object blockData) {
        try {
            Object v = (Object) AS_STRING.invokeExact(blockData);
            return v instanceof String ? (String) v : AIR;
        } catch (Throwable t) {
            return AIR;
        }
    }

    /** Single-block read, used by calibration and by the pre-1.13 path. */
    private static String readOne(ChunkSnapshot snap, int x, int y, int z) {
        if (MODERN) {
            Object bd;
            try {
                bd = BLOCK_AT == null ? null : (Object) BLOCK_AT.invokeExact(snap, x, y, z);
            } catch (Throwable t) {
                bd = null;
            }
            return bd == null ? AIR : asString(bd);
        }
        int id = 0, data = 0;
        try {
            if (TYPE_ID_AT != null) id = ((Integer) (Object) TYPE_ID_AT.invokeExact(snap, x, y, z)).intValue();
        } catch (Throwable ignored) {}
        Object d = Reflect.invoke(SNAP_BLOCK_DATA, snap, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
        if (d instanceof Integer) data = ((Integer) d).intValue();
        return legacy(id, data);
    }

    private static String legacy(int typeId, int data) {
        Object m = Reflect.invoke(MATERIAL_BY_ID, null, Integer.valueOf(typeId));
        String name = m instanceof Material ? ((Material) m).name() : ("ID_" + typeId);
        return name + ":" + data;
    }

    /**
     * The state string for a live block, in the same form the palette uses.
     * Must run on the block's owning thread.
     */
    public static String stateOf(Block b) {
        if (b == null) return AIR;
        if (MODERN) {
            Object bd = Reflect.invoke(BLOCK_GET_BLOCK_DATA, b);
            Object v = bd == null ? null : Reflect.invoke(BLOCK_DATA_AS_STRING, bd);
            return v instanceof String ? (String) v : AIR;
        }
        return legacy(b.getType().getId(), b.getData());
    }
}
