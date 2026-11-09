package dev.overlook.analytics;

import java.time.Instant;

/** @param stale verdadeiro quando a Vercel recusou a consulta e o valor veio do cache vencido */
public record Cached<T>(T value, Instant fetchedAt, boolean stale) {
}
