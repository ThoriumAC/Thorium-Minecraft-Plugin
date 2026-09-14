package ac.thorium.mc.plugin.compat;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs plugin code so that no exception ever reaches the server, logging at most one trace per site per window. */
public final class ErrorGate {
    private static final class Site { volatile long lastLogged = Long.MIN_VALUE; final AtomicLong suppressed = new AtomicLong(); }

    private final Logger log;
    private final long windowMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Site> sites = new ConcurrentHashMap<>();

    public ErrorGate(Logger log, long windowMs, LongSupplier clock) { this.log = log; this.windowMs = windowMs; this.clock = clock; }

    public void run(String site, Runnable body) {
        try { body.run(); } catch (Throwable t) { report(site, t); }
    }

    public <T> T call(String site, Callable<T> body, T fallback) {
        try { return body.call(); } catch (Throwable t) { report(site, t); return fallback; }
    }

    public long suppressed(String site) { Site s = sites.get(site); return s == null ? 0 : s.suppressed.get(); }

    private void report(String site, Throwable t) {
        Site s = sites.computeIfAbsent(site, k -> new Site());
        long now = clock.getAsLong();
        if (s.lastLogged == Long.MIN_VALUE || now - s.lastLogged >= windowMs) {
            long n = s.suppressed.getAndSet(0);
            s.lastLogged = now;
            String suffix = n > 0 ? " (" + n + " suppressed since last report)" : "";
            log.log(Level.WARNING, "Thorium: error in " + site + suffix, t);
        } else {
            s.suppressed.incrementAndGet();
        }
    }
}
