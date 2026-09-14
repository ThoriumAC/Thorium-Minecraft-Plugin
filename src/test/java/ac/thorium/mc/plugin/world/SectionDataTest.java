package ac.thorium.mc.plugin.world;

import ac.thorium.mc.proto.Section;
import ac.thorium.mc.proto.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SectionDataTest {
    private static String[] filled(String s) {
        String[] a = new String[SectionData.BLOCKS];
        Arrays.fill(a, s);
        return a;
    }

    @Test
    void offsetRoundTripsYzx() {
        for (int y = 0; y < 16; y += 5) {
            for (int z = 0; z < 16; z += 3) {
                for (int x = 0; x < 16; x += 7) {
                    int o = SectionData.offset(x, y, z);
                    assertEquals(x, SectionData.offsetX(o));
                    assertEquals(y, SectionData.offsetY(o));
                    assertEquals(z, SectionData.offsetZ(o));
                }
            }
        }
        // Minecraft's own ordering: y is the most significant, x the least.
        assertEquals(0, SectionData.offset(0, 0, 0));
        assertEquals(1, SectionData.offset(1, 0, 0));
        assertEquals(16, SectionData.offset(0, 0, 1));
        assertEquals(256, SectionData.offset(0, 1, 0));
        assertEquals(4095, SectionData.offset(15, 15, 15));
    }

    @Test
    void uniformSectionCarriesNoIndices() {
        SectionData d = SectionData.of(filled("minecraft:air"));
        assertTrue(d.isUniform());
        assertEquals(0, d.bitsPerIndex());
        assertEquals(1, d.palette().size());
        assertEquals("minecraft:air", d.blockAt(0));
        assertEquals("minecraft:air", d.blockAt(4095));
        assertEquals(0, d.toProto(pos()).getIndices().size());
    }

    @Test
    void smallPaletteUsesOneBytePerIndex() {
        String[] a = filled("minecraft:stone");
        a[SectionData.offset(3, 4, 5)] = "minecraft:diamond_ore";
        SectionData d = SectionData.of(a);
        assertEquals(8, d.bitsPerIndex());
        assertEquals(2, d.palette().size());
        assertEquals(SectionData.BLOCKS, d.toProto(pos()).getIndices().size());
        assertEquals("minecraft:diamond_ore", d.blockAt(SectionData.offset(3, 4, 5)));
        assertEquals("minecraft:stone", d.blockAt(SectionData.offset(3, 4, 6)));
    }

    @Test
    void largePaletteUsesTwoBytesPerIndex() {
        String[] a = new String[SectionData.BLOCKS];
        for (int i = 0; i < a.length; i++) a[i] = "block:" + (i % 300);
        SectionData d = SectionData.of(a);
        assertEquals(16, d.bitsPerIndex());
        assertEquals(300, d.palette().size());
        assertEquals(SectionData.BLOCKS * 2, d.toProto(pos()).getIndices().size());
        for (int i = 0; i < a.length; i += 97) assertEquals(a[i], d.blockAt(i));
    }

    @Test
    void everyBlockRoundTripsThroughTheEncoding() {
        String[] a = new String[SectionData.BLOCKS];
        for (int i = 0; i < a.length; i++) a[i] = "s" + (i % 251);
        SectionData d = SectionData.of(a);
        for (int i = 0; i < a.length; i++) assertEquals(a[i], d.blockAt(i), "offset " + i);
    }

    @Test
    void identicalContentHashesEqual() {
        String[] a = filled("minecraft:stone");
        a[7] = "minecraft:dirt";
        assertEquals(SectionData.of(a).contentHash(), SectionData.of(a.clone()).contentHash());
    }

    @Test
    void differentContentHashesDiffer() {
        String[] a = filled("minecraft:stone");
        String[] b = filled("minecraft:stone");
        b[7] = "minecraft:dirt";
        assertNotEquals(SectionData.of(a).contentHash(), SectionData.of(b).contentHash());
    }

    @Test
    void paletteSplitDoesNotCollide() {
        // The separator in the hash keeps ["ab","c"] distinct from ["a","bc"].
        String[] a = filled("ab");
        a[0] = "c";
        String[] b = filled("a");
        b[0] = "bc";
        assertNotEquals(SectionData.of(a).contentHash(), SectionData.of(b).contentHash());
    }

    @Test
    void uniformBuildsWithoutReadingBlocks() {
        SectionData d = SectionData.uniform("minecraft:air");
        assertTrue(d.isUniform());
        assertEquals(0, d.bitsPerIndex());
        assertEquals("minecraft:air", d.blockAt(0));
        assertEquals("minecraft:air", d.blockAt(4095));
        // Must be byte-identical to the slow path, or a fast-path section would
        // look like a change and resend every time.
        assertEquals(SectionData.of(filled("minecraft:air")).contentHash(), d.contentHash());
    }

    @Test
    void paletteFormMatchesTheStringArrayForm() {
        String[] states = filled("minecraft:stone");
        states[SectionData.offset(2, 3, 4)] = "minecraft:dirt";
        states[SectionData.offset(5, 6, 7)] = "minecraft:sand";

        int[] ids = new int[SectionData.BLOCKS];
        ids[SectionData.offset(2, 3, 4)] = 1;
        ids[SectionData.offset(5, 6, 7)] = 2;
        SectionData viaPalette = SectionData.of(
                Arrays.asList("minecraft:stone", "minecraft:dirt", "minecraft:sand"), ids);

        SectionData viaStrings = SectionData.of(states);
        assertEquals(viaStrings.contentHash(), viaPalette.contentHash());
        for (int i = 0; i < SectionData.BLOCKS; i += 37) {
            assertEquals(viaStrings.blockAt(i), viaPalette.blockAt(i), "offset " + i);
        }
    }

    @Test
    void paletteFormWidensPastAByte() {
        int[] ids = new int[SectionData.BLOCKS];
        java.util.List<String> palette = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) palette.add("b" + i);
        for (int i = 0; i < SectionData.BLOCKS; i++) ids[i] = i % 300;
        SectionData d = SectionData.of(palette, ids);
        assertEquals(16, d.bitsPerIndex());
        for (int i = 0; i < SectionData.BLOCKS; i += 53) assertEquals("b" + (i % 300), d.blockAt(i));
    }

    @Test
    void paletteFormRejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> SectionData.of(Arrays.asList("a"), new int[10]));
        assertThrows(IllegalArgumentException.class,
                () -> SectionData.of(new java.util.ArrayList<String>(), new int[SectionData.BLOCKS]));
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> SectionData.of(new String[10]));
    }

    @Test
    void nullStatesEncodeAsEmpty() {
        String[] a = filled("minecraft:stone");
        a[3] = null;
        assertEquals("", SectionData.of(a).blockAt(3));
    }

    @Test
    void protoCarriesPositionAndPalette() {
        String[] a = filled("minecraft:stone");
        a[1] = "minecraft:dirt";
        Section s = SectionData.of(a).toProto(pos());
        assertEquals("overworld", s.getPos().getDimension());
        assertEquals(-3, s.getPos().getX());
        assertEquals(4, s.getPos().getY());
        assertEquals(9, s.getPos().getZ());
        assertEquals(Arrays.asList("minecraft:stone", "minecraft:dirt"), s.getPaletteList());
    }

    private static SectionPos pos() {
        return SectionPos.newBuilder().setDimension("overworld").setX(-3).setY(4).setZ(9).build();
    }
}
