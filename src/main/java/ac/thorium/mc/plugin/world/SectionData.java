package ac.thorium.mc.plugin.world;

import ac.thorium.mc.proto.Section;
import ac.thorium.mc.proto.SectionPos;
import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One 16x16x16 section, palette-encoded exactly as {@code world.proto} describes:
 * entries in Minecraft's YZX order, one byte per index while the palette fits in
 * 256 entries and two little-endian bytes past that, and no index array at all
 * for a uniform section.
 *
 * <p>Pure data — no Bukkit types — so the encoding is unit-testable and the
 * Bukkit-touching sampler stays a thin adapter over it.
 */
public final class SectionData {
    public static final int BLOCKS = 4096;

    private final List<String> palette;
    private final int bitsPerIndex;
    private final byte[] indices;
    private final long contentHash;

    private SectionData(List<String> palette, int bitsPerIndex, byte[] indices, long contentHash) {
        this.palette = palette;
        this.bitsPerIndex = bitsPerIndex;
        this.indices = indices;
        this.contentHash = contentHash;
    }

    /** Section-local (x, y, z) to its offset, matching Minecraft's own ordering. */
    public static int offset(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    public static int offsetX(int offset) { return offset & 15; }
    public static int offsetY(int offset) { return (offset >> 8) & 15; }
    public static int offsetZ(int offset) { return (offset >> 4) & 15; }

    /**
     * A section that is one state everywhere — air above the terrain, stone deep
     * below. Two thirds of sections measured on real terrain are uniform, and this
     * builds one without touching a single block.
     */
    public static SectionData uniform(String block) {
        List<String> palette = new ArrayList<String>(1);
        palette.add(block == null ? "" : block);
        byte[] none = new byte[0];
        return new SectionData(palette, 0, none, hash(palette, none));
    }

    /**
     * Encodes from a palette and 4096 palette ids already in YZX order — the form
     * {@link SnapshotReader} produces directly, skipping the intermediate
     * {@code String[4096]} and its per-block string hashing.
     *
     * @param palette distinct states, indexed by the values in ids; read, not retained
     * @param ids     one palette index per block
     */
    public static SectionData of(List<String> palette, int[] ids) {
        if (ids.length != BLOCKS) {
            throw new IllegalArgumentException("expected " + BLOCKS + " ids, got " + ids.length);
        }
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("empty palette");
        }
        List<String> own = new ArrayList<String>(palette);
        int bits;
        byte[] indices;
        if (own.size() <= 1) {
            bits = 0;
            indices = new byte[0];
        } else if (own.size() <= 256) {
            bits = 8;
            indices = new byte[BLOCKS];
            for (int i = 0; i < BLOCKS; i++) indices[i] = (byte) ids[i];
        } else {
            bits = 16;
            indices = new byte[BLOCKS * 2];
            for (int i = 0; i < BLOCKS; i++) {
                indices[i * 2] = (byte) (ids[i] & 0xFF);
                indices[i * 2 + 1] = (byte) ((ids[i] >>> 8) & 0xFF);
            }
        }
        return new SectionData(own, bits, indices, hash(own, indices));
    }

    /**
     * Encodes 4096 block state strings in YZX order. The array is read, not retained.
     *
     * @throws IllegalArgumentException if states is not exactly 4096 long
     */
    public static SectionData of(String[] states) {
        if (states.length != BLOCKS) {
            throw new IllegalArgumentException("expected " + BLOCKS + " states, got " + states.length);
        }
        Map<String, Integer> ids = new HashMap<String, Integer>();
        List<String> palette = new ArrayList<String>();
        int[] raw = new int[BLOCKS];
        for (int i = 0; i < BLOCKS; i++) {
            String s = states[i] == null ? "" : states[i];
            Integer id = ids.get(s);
            if (id == null) {
                id = Integer.valueOf(palette.size());
                ids.put(s, id);
                palette.add(s);
            }
            raw[i] = id.intValue();
        }
        int bits;
        byte[] indices;
        if (palette.size() <= 1) {
            bits = 0;
            indices = new byte[0];
        } else if (palette.size() <= 256) {
            bits = 8;
            indices = new byte[BLOCKS];
            for (int i = 0; i < BLOCKS; i++) indices[i] = (byte) raw[i];
        } else {
            bits = 16;
            indices = new byte[BLOCKS * 2];
            for (int i = 0; i < BLOCKS; i++) {
                indices[i * 2] = (byte) (raw[i] & 0xFF);
                indices[i * 2 + 1] = (byte) ((raw[i] >>> 8) & 0xFF);
            }
        }
        return new SectionData(palette, bits, indices, hash(palette, indices));
    }

    /** 64-bit FNV-1a over the palette and indices; used to skip resending unchanged sections. */
    private static long hash(List<String> palette, byte[] indices) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < palette.size(); i++) {
            String s = palette.get(i);
            for (int j = 0; j < s.length(); j++) {
                h = (h ^ s.charAt(j)) * 0x100000001b3L;
            }
            h = (h ^ 0xFF) * 0x100000001b3L;   // separator, so ["ab","c"] and ["a","bc"] differ
        }
        for (int i = 0; i < indices.length; i++) {
            h = (h ^ (indices[i] & 0xFF)) * 0x100000001b3L;
        }
        return h;
    }

    /** The state string at a section-local offset. */
    public String blockAt(int offset) {
        if (offset < 0 || offset >= BLOCKS) throw new IndexOutOfBoundsException("offset " + offset);
        if (bitsPerIndex == 0) return palette.get(0);
        if (bitsPerIndex == 8) return palette.get(indices[offset] & 0xFF);
        return palette.get((indices[offset * 2] & 0xFF) | ((indices[offset * 2 + 1] & 0xFF) << 8));
    }

    public Section toProto(SectionPos pos) {
        return toProto(pos, "");
    }

    /**
     * @param biome name for the section's centre, or "" when the server has none.
     *              It rides along with the blocks rather than in the content hash:
     *              a biome cannot change under a section that is otherwise
     *              unchanged, so hashing it would only cost re-sends.
     */
    public Section toProto(SectionPos pos, String biome) {
        Section.Builder b = Section.newBuilder()
                .setPos(pos)
                .addAllPalette(palette)
                .setBitsPerIndex(bitsPerIndex)
                .setIndices(ByteString.copyFrom(indices));
        if (biome != null && !biome.isEmpty()) b.setBiome(biome);
        return b.build();
    }

    /** Rough encoded size, for the sampler's per-frame byte budget. */
    public int approximateBytes() {
        int n = indices.length + 16;
        for (int i = 0; i < palette.size(); i++) n += palette.get(i).length() + 2;
        return n;
    }

    public List<String> palette() { return palette; }
    public int bitsPerIndex() { return bitsPerIndex; }
    public long contentHash() { return contentHash; }
    public boolean isUniform() { return bitsPerIndex == 0; }
}
