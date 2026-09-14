package ac.thorium.mc.plugin.telemetry;

import java.util.concurrent.atomic.AtomicInteger;

/** server_moved for the next N movement samples after a teleport / velocity / knockback (spec §10: 3). */
public final class MovedCounter {
    private final int samples;
    private final AtomicInteger remaining = new AtomicInteger();

    public MovedCounter(int samples) { this.samples = samples; }
    public void mark() { remaining.set(samples); }
    public boolean consume() {
        while (true) {
            int r = remaining.get();
            if (r <= 0) return false;
            if (remaining.compareAndSet(r, r - 1)) return true;
        }
    }
}
