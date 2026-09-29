package ac.thorium.mc.plugin.telemetry;

import ac.thorium.mc.proto.PlayerBatch;
import ac.thorium.mc.proto.Sample;

import java.util.concurrent.atomic.AtomicLong;

public final class PlayerRing {
    private final Sample.Builder[] buf;
    private final int mask;
    private final AtomicLong head = new AtomicLong();
    private final AtomicLong tail = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public PlayerRing(int capacity) {
        int cap = 1; while (cap < capacity) cap <<= 1;
        buf = new Sample.Builder[cap]; mask = cap - 1;
    }

    // Single producer at a time (SampleBuffer locks per player). Full ring drops the new sample; overwriting would reorder the stream.
    public boolean offer(Sample.Builder s) {
        long h = head.get();
        if (h - tail.get() >= buf.length) { dropped.incrementAndGet(); return false; }
        buf[(int) (h & mask)] = s;
        head.lazySet(h + 1);
        return true;
    }

    public int drainTo(PlayerBatch.Builder out) { return drainTo(out, false); }

    /** With {@code relative}, times and ticks become offsets from the first drained sample's. */
    public int drainTo(PlayerBatch.Builder out, boolean relative) {
        long t = tail.get();
        long h = head.get();
        int n = 0;
        long baseTime = 0, baseTick = 0;
        while (t < h) {
            Sample.Builder s = buf[(int) (t & mask)];
            buf[(int) (t & mask)] = null;
            if (s != null) {
                if (relative) {
                    if (n == 0) { baseTime = s.getClientTimeMs(); baseTick = s.getTick(); out.setRelativeTimes(true).setBaseTimeMs(baseTime).setBaseTick(baseTick); }
                    s.setClientTimeMs(s.getClientTimeMs() - baseTime).setTick(s.getTick() - baseTick);
                }
                out.addSamples(s);
                n++;
            }
            t++;
        }
        tail.lazySet(t);
        return n;
    }

    public long dropped() { return dropped.get(); }
    public boolean isEmpty() { return head.get() == tail.get(); }
}
