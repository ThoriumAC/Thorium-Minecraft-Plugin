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
    private static final Method SNAP_BIOME_XYZ =
            Reflect.method(ChunkSnapshot.class, "getBiome", int.class, int.class, int.class);
    private static final Method SNAP_BIOME_XZ =
            Reflect.method(ChunkSnapshot.class, "getBiome", int.class, int.class);
    private static final Method BIOME_GET_KEY = Reflect.method("org.bukkit.block.Biome", "getKey");

    private static final boolean MODERN =
            SNAP_BLOCK_DATA != null && !int.class.equals(SNAP_BLOCK_DATA.getReturnType()) && BLOCK_DATA_AS_STRING != null;

    public static final String AIR = MODERN ? "minecraft:air" : "AIR:0";

    private static final MethodHandle BLOCK_AT = handle(SNAP_BLOCK_DATA);
    private static final MethodHandle TYPE_ID_AT = handle(SNAP_BLOCK_TYPE_ID);
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

    private static volatile int emptyFastPath;

    private SnapshotReader() {}

    public static boolean modern() { return MODERN; }

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

    static String biomeName(Object biome) {
        if (biome == null) return "";
        if (BIOME_GET_KEY != null) {
            try {
                Object key = BIOME_GET_KEY.invoke(biome);
                if (key != null) return key.toString();
            } catch (Throwable ignored) {
            }
        }
        String name = biome.toString();
        return name.isEmpty() ? "" : "minecraft:" + name.toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean emptyFastPathEnabled() { return emptyFastPath == 1; }

    public static java.util.List<String> branches() {
        java.util.List<String> l = new ArrayList<String>();
        l.add("block-states: " + (MODERN ? "1.13+ BlockData.getAsString"
                : SNAP_BLOCK_TYPE_ID != null ? "legacy MATERIAL:data"
                : "NONE, the mirror cannot read blocks on this server"));
        l.add("block-handle: " + (BLOCK_AT != null ? "MethodHandle" : TYPE_ID_AT != null ? "MethodHandle (legacy ids)" : "none"));
        l.add("biome: " + (SNAP_BIOME_XYZ != null ? "getBiome(x,y,z)"
                : SNAP_BIOME_XZ != null ? "getBiome(x,z)"
                : "unavailable, sections stream without a biome"));
        l.add("biome-name: " + (BIOME_GET_KEY != null ? "NamespacedKey.getKey()" : "enum name"));
        l.add("min-height: " + (WORLD_MIN_HEIGHT != null ? "World.getMinHeight()" : "assumed 0 (pre-1.18)"));
        l.add("empty-section: " + (SNAP_SECTION_EMPTY == null ? "absent"
                : emptyFastPath == 1 ? "trusted" : emptyFastPath == -1 ? "rejected" : "not yet calibrated"));
        l.add("live-block: " + (BLOCK_GET_BLOCK_DATA != null ? "Block.getBlockData()" : "legacy id+data"));
        return l;
    }

    public static boolean readable() { return BLOCK_AT != null || TYPE_ID_AT != null; }

    public static int minHeight(World w) {
        Object v = Reflect.invoke(WORLD_MIN_HEIGHT, w);
        return v instanceof Integer ? ((Integer) v).intValue() : 0;
    }

    public static int minSectionY(World w) { return minHeight(w) >> 4; }

    public static int maxSectionY(World w) { return (w.getMaxHeight() - 1) >> 4; }

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
                            emptyFastPath = -1;
                            return;
                        }
                    }
                }
            }
        }
        emptyFastPath = claimedEmpty > 0 ? 1 : 0;
    }

    private static boolean isEmpty(ChunkSnapshot snap, int sectionY, int minSy) {
        if (emptyFastPath != 1) return false;
        Object v = Reflect.invoke(SNAP_SECTION_EMPTY, snap, Integer.valueOf(sectionY - minSy));
        return v instanceof Boolean && ((Boolean) v).booleanValue();
    }

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
