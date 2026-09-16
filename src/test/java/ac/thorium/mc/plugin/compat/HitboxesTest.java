package ac.thorium.mc.plugin.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HitboxesTest {
    @Test
    void knowsTheMobsA1_8ServerWillNotMeasure() {
        assertArrayEquals(new float[] { 0.6f, 1.95f }, Hitboxes.vanilla("VILLAGER", 0));
        assertArrayEquals(new float[] { 0.6f, 1.8f }, Hitboxes.vanilla("PLAYER", 0));
        assertArrayEquals(new float[] { 1.4f, 0.9f }, Hitboxes.vanilla("SPIDER", 0));
    }

    @Test
    void scalesSlimesByTheirSize() {
        float[] small = Hitboxes.vanilla("SLIME", 1);
        float[] big = Hitboxes.vanilla("MAGMA_CUBE", 4);
        assertNotNull(small);
        assertNotNull(big);
        assertEquals(small[0], small[1], 1e-6, "slimes are cubes");
        assertEquals(4 * small[0], big[0], 1e-5);
    }

    @Test
    void saysNothingRatherThanGuessing() {
        // Reach bans on distance, and too small a box inflates the distance, so an
        // entity nobody has a size for must leave the check sitting out.
        assertNull(Hitboxes.vanilla("SOME_MODDED_BEAST", 0));
        assertNull(Hitboxes.vanilla(null, 0));
        assertNull(Hitboxes.vanilla("SLIME", 0), "a slime whose size the server will not state");
    }

    @Test
    void isNotCaseSensitiveAboutTypeNames() {
        assertArrayEquals(Hitboxes.vanilla("CREEPER", 0), Hitboxes.vanilla("creeper", 0));
    }
}
