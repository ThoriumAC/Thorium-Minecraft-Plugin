package ac.thorium.mc.plugin.world;

import ac.thorium.mc.proto.Section;
import ac.thorium.mc.proto.SectionPos;
import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public static int offset(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    public static int offsetX(int offset) { return offset & 15; }
    public static int offsetY(int offset) { return (offset >> 8) & 15; }
    public static int offsetZ(int offset) { return (offset >> 4) & 15; }

    public static SectionData uniform(String block) {
        List<String> palette = new ArrayList<String>(1);
        palette.add(block == null ? "" : block);
        byte[] none = new byte[0];
        return new SectionData(palette, 0, none, hash(palette, none));
    }

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

    private static long hash(List<String> palette, byte[] indices) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < palette.size(); i++) {
            String s = palette.get(i);
            for (int j = 0; j < s.length(); j++) {
                h = (h ^ s.charAt(j)) * 0x100000001b3L;
            }
            h = (h ^ 0xFF) * 0x100000001b3L;
        }
        for (int i = 0; i < indices.length; i++) {
            h = (h ^ (indices[i] & 0xFF)) * 0x100000001b3L;
        }
        return h;
    }

    public String blockAt(int offset) {
        if (offset < 0 || offset >= BLOCKS) throw new IndexOutOfBoundsException("offset " + offset);
        if (bitsPerIndex == 0) return palette.get(0);
        if (bitsPerIndex == 8) return palette.get(indices[offset] & 0xFF);
        return palette.get((indices[offset * 2] & 0xFF) | ((indices[offset * 2 + 1] & 0xFF) << 8));
    }

    public Section toProto(SectionPos pos) {
        return toProto(pos, "");
    }

    public Section toProto(SectionPos pos, String biome) {
        Section.Builder b = Section.newBuilder()
                .setPos(pos)
                .addAllPalette(palette)
                .setBitsPerIndex(bitsPerIndex)
                .setIndices(ByteString.copyFrom(indices));
        if (biome != null && !biome.isEmpty()) b.setBiome(biome);
        return b.build();
    }

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
