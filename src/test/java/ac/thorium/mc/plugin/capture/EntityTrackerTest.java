package ac.thorium.mc.plugin.capture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityTrackerTest {
    @Test
    void rangeFollowsPacketsAndPinsSurvive() {
        EntityTracker t = new EntityTracker(12);
        UUID v = UUID.randomUUID();
        t.viewerAt(v, 0, 64, 0);
        t.spawn(v, 5, "minecraft:zombie", true, 5, 64, 5, 0, 0);
        t.spawn(v, 6, "minecraft:item", false, 1, 64, 1, 0, 0);
        t.spawn(v, 7, "minecraft:zombie", true, 40, 64, 0, 0, 0);
        assertTrue(t.contains(v, 5));
        assertFalse(t.contains(v, 6), "unwanted types are never streamed");
        assertFalse(t.contains(v, 7));

        List<Integer> in = new ArrayList<>();
        t.entered(v, k -> in.add(k.id));
        assertEquals(java.util.Collections.singletonList(5), in);

        t.get(v, 7).moveTo(10, 64, 0);
        in.clear();
        t.entered(v, k -> in.add(k.id));
        assertEquals(java.util.Collections.singletonList(7), in, "an entity walking into range is reported once");

        t.pin(v, 9);
        assertTrue(t.contains(v, 9));
        t.destroy(v, new int[]{5, 9});
        assertFalse(t.contains(v, 5));
        assertFalse(t.contains(v, 9));
        t.forget(v);
        assertNull(t.get(v, 7));
    }

    @Test
    void wantedKeepsLivingVehiclesAndProjectiles() {
        assertTrue(EntityTracker.wanted("zombie", true));
        assertTrue(EntityTracker.wanted("oak_boat", false));
        assertTrue(EntityTracker.wanted("arrow", false));
        assertFalse(EntityTracker.wanted("item_frame", false));
        assertFalse(EntityTracker.wanted("item", false));
    }
}
