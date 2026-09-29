package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.PlayerBatch;
import ac.thorium.mc.proto.Sample;
import ac.thorium.mc.proto.TickEndSample;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerRingTest {
    private static Sample.Builder s(int seq) { return Sample.newBuilder().setSeq(seq).setTickEnd(TickEndSample.getDefaultInstance()); }

    @Test
    void keepsOrderAndRejectsWhenFull() {
        PlayerRing r = new PlayerRing(4);
        for (int i = 1; i <= 6; i++) r.offer(s(i));
        PlayerBatch.Builder b = PlayerBatch.newBuilder();
        assertEquals(4, r.drainTo(b));
        assertEquals(1, b.getSamples(0).getSeq());
        assertEquals(4, b.getSamples(3).getSeq());
        assertEquals(2, r.dropped());
    }

    @Test
    void producerAndConsumerNeverLoseOrDuplicate() throws Exception {
        PlayerRing r = new PlayerRing(1024);
        int n = 200_000;
        CountDownLatch done = new CountDownLatch(1);
        Thread producer = new Thread(() -> { for (int i = 1; i <= n; i++) while (!r.offer(s(i))) Thread.yield(); done.countDown(); });
        producer.start();
        AtomicInteger last = new AtomicInteger();
        int seen = 0;
        while (seen < n) {
            PlayerBatch.Builder b = PlayerBatch.newBuilder();
            int k = r.drainTo(b);
            for (int i = 0; i < k; i++) { assertEquals(last.get() + 1, b.getSamples(i).getSeq()); last.set(b.getSamples(i).getSeq()); }
            seen += k;
            if (k == 0) Thread.yield();
        }
        done.await();
    }

    @Test
    void relativeDrainRebasesOnFirstSample() {
        PlayerRing r = new PlayerRing(8);
        r.offer(s(1).setClientTimeMs(1_700_000_000_000L).setTick(500));
        r.offer(s(2).setClientTimeMs(1_700_000_000_050L).setTick(501));
        PlayerBatch.Builder b = PlayerBatch.newBuilder();
        assertEquals(2, r.drainTo(b, true));
        assertTrue(b.getRelativeTimes());
        assertEquals(1_700_000_000_000L, b.getBaseTimeMs());
        assertEquals(500, b.getBaseTick());
        assertEquals(0, b.getSamples(0).getClientTimeMs());
        assertEquals(50, b.getSamples(1).getClientTimeMs());
        assertEquals(1, b.getSamples(1).getTick());
    }
}
