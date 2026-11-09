package dev.overlook.analytics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Espera exponencial depois de limite de requisições: 30 s, 60 s, 120 s (e fica em 120 s).
 * Várias consultas recusadas dentro da mesma janela contam como uma tentativa só.
 */
public final class RateLimitBackoff {

    private static final int[] STEPS = {30, 60, 120};
    private static final Duration SAME_ATTEMPT = Duration.ofSeconds(10);

    private final Clock clock;
    private int attempt = -1;
    private Instant lastBump = Instant.MIN;

    public RateLimitBackoff(Clock clock) {
        this.clock = clock;
    }

    public synchronized int onRateLimited() {
        Instant now = clock.instant();
        if (attempt < 0 || lastBump.plus(SAME_ATTEMPT).isBefore(now)) {
            attempt = Math.min(attempt + 1, STEPS.length - 1);
            lastBump = now;
        }
        return STEPS[attempt];
    }

    public synchronized void reset() {
        attempt = -1;
        lastBump = Instant.MIN;
    }
}
