package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.TransactionCause;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTrackerTest {
    @Test
    void idsStayInReservedRangeAndCycle() {
        TransactionTracker t = new TransactionTracker(() -> 0L);
        UUID p = UUID.randomUUID();
        int first = t.send(p, TransactionCause.TRANSACTION_CAUSE_PROBE);
        assertTrue(TransactionTracker.isOurs(first));
        assertEquals(TransactionTracker.ID_MAX - 1, first);
        for (int i = 0; i < TransactionTracker.ID_MAX - TransactionTracker.ID_MIN + 5; i++) {
            int id = t.send(p, TransactionCause.TRANSACTION_CAUSE_PROBE);
            assertTrue(TransactionTracker.isOurs(id), "id " + id);
            t.ack(p, id);
        }
        assertFalse(TransactionTracker.isOurs(1));
        assertFalse(TransactionTracker.isOurs(0));
        assertTrue((short) TransactionTracker.ID_MIN == TransactionTracker.ID_MIN, "must fit a window-confirmation short");
    }

    @Test
    void ackReturnsCauseAndRtt() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        TransactionTracker t = new TransactionTracker(now::get);
        UUID p = UUID.randomUUID();
        int id = t.send(p, TransactionCause.TRANSACTION_CAUSE_VELOCITY);
        now.addAndGet(48_000_000L);
        TransactionTracker.Ack a = t.ack(p, id);
        assertNotNull(a);
        assertEquals(TransactionCause.TRANSACTION_CAUSE_VELOCITY, a.cause);
        assertEquals(48L, a.rttMs);
        assertNull(t.ack(p, id), "second echo of the same id is ignored");
        assertNull(t.ack(p, 7), "server's own ping ids are not ours");
        assertNull(t.ack(UUID.randomUUID(), id), "ids are per player");
    }

    @Test
    void forgetDropsPending() {
        TransactionTracker t = new TransactionTracker(() -> 0L);
        UUID p = UUID.randomUUID();
        int id = t.send(p, TransactionCause.TRANSACTION_CAUSE_EXPLOSION);
        assertEquals(1, t.pending(p));
        t.forget(p);
        assertEquals(0, t.pending(p));
        assertNull(t.ack(p, id));
    }
}
