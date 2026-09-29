package ac.thorium.mc.plugin.capture;

import ac.thorium.mc.proto.PlayerEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
