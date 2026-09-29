package ac.thorium.mc.plugin.enforce;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MitigatorTest {
    @Test
    void cancelWindowCountsTicksAndClearsOnConfirm() {
        Mitigator.Windows w = new Mitigator.Windows();
        UUID p = UUID.randomUUID();
        w.open(p, 100, 3);
        assertTrue(w.shouldCancel(p, 100));
        assertTrue(w.shouldCancel(p, 103));
        assertFalse(w.shouldCancel(p, 104));
        w.open(p, 200, 3);
        w.teleportConfirmed(p);
        assertFalse(w.shouldCancel(p, 201));
    }

    @Test
    void setbackRateLimitIsFiveTicks() {
        Mitigator.Windows w = new Mitigator.Windows();
        UUID p = UUID.randomUUID();
        assertTrue(w.allowSetback(p, 100));
        assertFalse(w.allowSetback(p, 104));
        assertTrue(w.allowSetback(p, 105));
    }
}
