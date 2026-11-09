package dev.overlook.analytics;

import dev.overlook.vercel.model.Granularity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Período pedido pela UI. Buckets são alinhados em UTC, como a Vercel faz. O último bucket
 * pode estar aberto (em andamento).
 */
public record TimeRange(String key, Instant since, Instant until, Granularity granularity, Instant now) {

    public static final int MAX_DAYS = 400;

    public static TimeRange parse(String range, String sinceDate, String untilDate, Clock clock) {
        Instant now = clock.instant();
        if ((range == null || range.isBlank() || range.equals("custom")) && sinceDate != null && !sinceDate.isBlank()) {
            return custom(sinceDate, untilDate, now);
        }
        String key = range == null || range.isBlank() ? "7d" : range;
        return switch (key) {
            case "24h" -> {
                Instant since = now.truncatedTo(ChronoUnit.HOURS).minus(23, ChronoUnit.HOURS);
                yield new TimeRange(key, since, now, Granularity.HOUR, now);
            }
            case "7d", "30d", "90d" -> {
                int days = Integer.parseInt(key.substring(0, key.length() - 1));
                Instant since = now.truncatedTo(ChronoUnit.DAYS).minus(days - 1L, ChronoUnit.DAYS);
                yield new TimeRange(key, since, now, Granularity.DAY, now);
            }
            default -> throw new IllegalArgumentException("Período inválido: use 24h, 7d, 30d, 90d ou since/until");
        };
    }

    private static TimeRange custom(String sinceDate, String untilDate, Instant now) {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(sinceDate);
            to = untilDate == null || untilDate.isBlank() ? LocalDate.ofInstant(now, ZoneOffset.UTC)
                    : LocalDate.parse(untilDate);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Datas devem estar no formato AAAA-MM-DD");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("A data final é anterior à inicial");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new IllegalArgumentException("Período máximo: " + MAX_DAYS + " dias");
        }
        Instant since = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        if (!since.isBefore(now)) {
            throw new IllegalArgumentException("A data inicial está no futuro");
        }
        Instant until = end.isAfter(now) ? now : end;
        Granularity g = Duration.between(since, end).toHours() <= 48 ? Granularity.HOUR : Granularity.DAY;
        return new TimeRange("custom", since, until, g, now);
    }

    /** Período imediatamente anterior, com o mesmo número de buckets, todos fechados. */
    public TimeRange previous() {
        Duration span = granularity.duration().multipliedBy(buckets().size());
        return new TimeRange(key, since.minus(span), since, granularity, now);
    }

    /** O mesmo período cortado no fim do último bucket fechado. */
    public TimeRange closedOnly() {
        return new TimeRange(key, since, closedUntil(), granularity, now);
    }

    /**
     * Inícios dos buckets. Quando o período termina agora e "agora" cai exatamente na virada
     * de um bucket, o bucket que acabou de abrir também entra (vazio e em andamento).
     */
    public List<Instant> buckets() {
        List<Instant> list = new ArrayList<>();
        boolean endsNow = !until.isBefore(now);
        for (Instant t = since; t.isBefore(until) || (endsNow && t.equals(until)); t = t.plus(granularity.duration())) {
            list.add(t);
        }
        return list;
    }

    public boolean isOpen(Instant bucketStart) {
        return bucketStart.plus(granularity.duration()).isAfter(now);
    }

    /** Quantos buckets do início estão fechados. */
    public int closedBuckets() {
        int n = 0;
        for (Instant b : buckets()) {
            if (isOpen(b)) {
                break;
            }
            n++;
        }
        return n;
    }

    /** Fim do último bucket fechado. */
    public Instant closedUntil() {
        return since.plus(granularity.duration().multipliedBy(closedBuckets()));
    }
}
