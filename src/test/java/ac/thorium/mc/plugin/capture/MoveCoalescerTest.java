package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.EntityMove;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoveCoalescerTest {
    @Test
    void lastPositionPerEntityWinsWithinATick() {
        MoveCoalescer c = new MoveCoalescer();
        c.offer(7, 1, 64, 1, false, 0, 0, true);
        c.offer(7, 1.5, 64, 1, true, 90, 0, true);
        c.offer(8, 0, 70, 0, false, 0, 0, false);
        List<EntityMove> out = new ArrayList<>();
        c.drain(out::add);
        assertEquals(2, out.size());
        EntityMove m7 = out.get(0).getId() == 7 ? out.get(0) : out.get(1);
        assertEquals(1.5, m7.getPosition().getX());
        assertTrue(m7.getHasLook());
        assertEquals(90f, m7.getYaw());
        out.clear();
        c.drain(out::add);
        assertTrue(out.isEmpty(), "drained");
    }
}
