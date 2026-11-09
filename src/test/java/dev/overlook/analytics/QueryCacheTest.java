package dev.overlook.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.overlook.MutableClock;
import dev.overlook.vercel.error.RateLimited;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QueryCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T12:00:00Z"));
    private final QueryCache cache = new QueryCache(clock);
    private final AtomicInteger calls = new AtomicInteger();

    private Cached<Integer> get() {
        return cache.get("k", Duration.ofMinutes(5), calls::incrementAndGet);
    }

    @Test
    void servesFreshEntriesWithoutReloading() {
        assertThat(get().value()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(4));
        assertThat(get().value()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(get().value()).isEqualTo(2);
    }

    @Test
    void fallsBackToStaleEntryOnRateLimit() {
        get();
        clock.advance(Duration.ofMinutes(10));

        Cached<Integer> stale = cache.get("k", Duration.ofMinutes(5), () -> {
            throw new RateLimited(30);
        });

        assertThat(stale.stale()).isTrue();
        assertThat(stale.value()).isEqualTo(1);
    }

    @Test
    void rateLimitWithoutCacheIsRethrown() {
        assertThatThrownBy(() -> cache.get("other", Duration.ofMinutes(5), () -> {
            throw new RateLimited(30);
        })).isInstanceOf(RateLimited.class);
    }

    @Test
    void backoffIsExponentialAndResets() {
        RateLimitBackoff b = new RateLimitBackoff(clock);

        assertThat(b.onRateLimited()).isEqualTo(30);
        assertThat(b.onRateLimited()).as("mesma janela conta como uma tentativa").isEqualTo(30);
        clock.advance(Duration.ofSeconds(31));
        assertThat(b.onRateLimited()).isEqualTo(60);
        clock.advance(Duration.ofSeconds(61));
        assertThat(b.onRateLimited()).isEqualTo(120);
        clock.advance(Duration.ofSeconds(121));
        assertThat(b.onRateLimited()).isEqualTo(120);
        b.reset();
        assertThat(b.onRateLimited()).isEqualTo(30);
    }
}
