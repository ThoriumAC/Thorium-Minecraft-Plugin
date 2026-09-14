package ac.thorium.mc.plugin.transport;

import java.util.Random;

public final class Backoff {
    private final long initialMs, maxMs;
    private final Random random;
    private int attempts;

    public Backoff(long initialMs, long maxMs, Random random) { this.initialMs = initialMs; this.maxMs = maxMs; this.random = random; }

    public synchronized long nextDelayMs() {
        double base = Math.min((double) maxMs, initialMs * Math.pow(2, Math.min(attempts, 30)));
        attempts++;
        double jitter = 1.0 + (random.nextDouble() * 0.4 - 0.2);
        return Math.round(base * jitter);
    }

    public synchronized void reset() { attempts = 0; }
    public synchronized int attempts() { return attempts; }
}
