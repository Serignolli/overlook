package dev.overlook.analytics;

import dev.overlook.vercel.error.RateLimited;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Cache em memória por chave de consulta, para poupar o limite de requisições da Vercel.
 * Entradas vencidas continuam guardadas como reserva para quando a Vercel responde com
 * limite de requisições.
 */
public final class QueryCache {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final int MAX_ENTRIES = 500;

    private record Entry(Object value, Instant fetchedAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public QueryCache(Clock clock) {
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public <T> Cached<T> get(String key, Duration ttl, Supplier<T> loader) {
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry != null && entry.fetchedAt().plus(ttl).isAfter(now)) {
            return new Cached<>((T) entry.value(), entry.fetchedAt(), false);
        }
        try {
            T value = loader.get();
            if (entries.size() >= MAX_ENTRIES) {
                evictOldest();
            }
            entries.put(key, new Entry(value, now));
            return new Cached<>(value, now, false);
        } catch (RateLimited e) {
            if (entry != null) {
                return new Cached<>((T) entry.value(), entry.fetchedAt(), true);
            }
            throw e;
        }
    }

    public void invalidateAll() {
        entries.clear();
    }

    private void evictOldest() {
        entries.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().fetchedAt()))
                .ifPresent(e -> entries.remove(e.getKey()));
    }
}
