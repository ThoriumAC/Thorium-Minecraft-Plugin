package ac.thorium.mc.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThoriumStatsTest {
    @AfterEach void unbind() { ThoriumStats.bind(null); }

    @Test void keys() {
        assertTrue(ThoriumStats.validKey("bedwars_wins"));
        assertTrue(ThoriumStats.validKey("a"));
        assertFalse(ThoriumStats.validKey(""));
        assertFalse(ThoriumStats.validKey(null));
        assertFalse(ThoriumStats.validKey("Bedwars"));
        assertFalse(ThoriumStats.validKey("has space"));
        assertFalse(ThoriumStats.validKey("x123456789012345678901234567890123"));
    }

    @Test void badInputNeverThrowsOrRecords() {
        List<String> got = new ArrayList<>();
        ThoriumStats.bind((p, kind, key, amount) -> got.add(kind + ":" + key + ":" + amount));
        assertDoesNotThrow(() -> ThoriumStats.increment(null, "wins", 1));
        assertDoesNotThrow(() -> ThoriumStats.increment(null, "Bad Key", 1));
        assertDoesNotThrow(() -> ThoriumStats.max(null, "best", Double.NaN));
        assertTrue(got.isEmpty());
    }

    @Test void unboundIsSilent() {
        assertDoesNotThrow(() -> ThoriumStats.increment(null, "wins", 1));
    }
}
