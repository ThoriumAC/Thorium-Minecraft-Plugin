package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.PlayerEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A boost is the one push an elytra pilot may legitimately give themselves.
 * Without it on the wire the engine has to let every climb pass, so the source
 * has to survive the trip and has to be named.
 */
class EventFactoryBoostTest {
    @Test
    void carriesTheSourceThatCausedIt() {
        PlayerEvent e = EventFactory.boost("firework").build();
        assertTrue(e.hasBoost());
        assertEquals("firework", e.getBoost().getSource());
        assertEquals("riptide", EventFactory.boost("riptide").build().getBoost().getSource());
    }

    @Test
    void neverCarriesANullSource() {
        assertEquals("", EventFactory.boost(null).build().getBoost().getSource());
    }
}
