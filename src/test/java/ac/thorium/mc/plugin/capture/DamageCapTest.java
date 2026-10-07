package ac.thorium.mc.plugin.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DamageCapTest {
    @Test void capsAtRemainingHealthInHearts() {
        assertEquals(3.0, DamageCap.hearts(6, 20));
        assertEquals(10.0, DamageCap.hearts(1e9, 20));
        assertEquals(2.5, DamageCap.hearts(Double.POSITIVE_INFINITY, 5));
        assertEquals(0.0, DamageCap.hearts(-4, 20));
        assertEquals(0.0, DamageCap.hearts(Double.NaN, 20));
        assertEquals(4.0, DamageCap.hearts(8, -1));
    }
}
