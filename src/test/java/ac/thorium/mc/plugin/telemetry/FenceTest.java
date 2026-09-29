package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.TransactionCause;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FenceTest {
    @Test
    void pendingRemembersFencedSeq() {
        TransactionTracker t = new TransactionTracker(() -> 0L);
        UUID p = UUID.randomUUID();
        int id = t.send(p, TransactionCause.TRANSACTION_CAUSE_OUTBOUND, 4242L);
        TransactionTracker.Ack a = t.ack(p, id);
        assertNotNull(a);
        assertEquals(4242L, a.fencesSeq);
        assertEquals(TransactionCause.TRANSACTION_CAUSE_OUTBOUND, a.cause);
    }
}
