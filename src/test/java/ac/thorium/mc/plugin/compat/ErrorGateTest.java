package ac.thorium.mc.plugin.compat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ErrorGateTest {
    @Test
    void logsOncePerWindowAndCountsTheRest() {
        Logger log = Logger.getLogger("ErrorGateTest");
        log.setUseParentHandlers(false);
        List<LogRecord> records = new ArrayList<>();
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        long[] now = {0};
        ErrorGate gate = new ErrorGate(log, 60_000, () -> now[0]);
        Runnable boom = () -> { throw new IllegalStateException("boom"); };

        gate.run("capture", boom);
        gate.run("capture", boom);
        gate.run("capture", boom);
        assertEquals(1, records.size());
        assertNotNull(records.get(0).getThrown());
        assertEquals(2, gate.suppressed("capture"));

        now[0] = 60_001;
        gate.run("capture", boom);
        assertEquals(2, records.size());
        assertTrue(records.get(1).getMessage().contains("2 suppressed"));
        assertEquals(0, gate.suppressed("capture"));
    }

    @Test
    void callReturnsFallbackOnThrow() {
        ErrorGate gate = new ErrorGate(Logger.getLogger("x"), 1000, () -> 0L);
        assertEquals("fb", gate.call("s", () -> { throw new RuntimeException(); }, "fb"));
        assertEquals("ok", gate.call("s", () -> "ok", "fb"));
    }
}
