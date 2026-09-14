package ac.thorium.mc.plugin.transport;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BackoffTest {
    @Test
    void doublesFromInitialToMaxWithJitter() {
        Backoff b = new Backoff(2000, 60000, new Random(1));
        long[] expected = {2000, 4000, 8000, 16000, 32000, 60000, 60000};
        for (long e : expected) {
            long d = b.nextDelayMs();
            assertTrue(d >= e * 0.8 && d <= e * 1.2, "delay " + d + " not within 20% of " + e);
        }
        assertEquals(7, b.attempts());
    }

    @Test
    void resetRestarts() {
        Backoff b = new Backoff(2000, 60000, new Random(2));
        b.nextDelayMs(); b.nextDelayMs(); b.nextDelayMs();
        b.reset();
        assertEquals(0, b.attempts());
        long d = b.nextDelayMs();
        assertTrue(d >= 1600 && d <= 2400);
    }
}
