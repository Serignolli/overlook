package dev.overlook.analytics;

import dev.overlook.vercel.error.RateLimited;
import dev.overlook.vercel.error.VercelException;
import java.time.Instant;
import java.util.List;

/** Respostas do {@link AnalyticsService}, serializadas como estão pela API local. */
public final class Results {

    private Results() {
    }

    public record RangeInfo(String key, Instant since, Instant until, String granularity, int buckets, int closedBuckets) {
        static RangeInfo of(TimeRange r) {
            return new RangeInfo(r.key(), r.since(), r.until(), r.granularity().flag(), r.buckets().size(),
                    r.closedBuckets());
        }
    }

    /** Erro de um bloco da tela; os outros blocos seguem funcionando. */
    public record BlockError(String code, String message, String action, Integer retryAfterSeconds) {
        public static BlockError of(VercelException e) {
            Integer retry = e instanceof RateLimited r ? r.retryAfterSeconds() : null;
            return new BlockError(e.code(), e.getMessage(), e.action(), retry);
        }
    }

    /**
     * @param change variação contra o período anterior, só com buckets fechados
     * @param note   texto que acompanha o número na UI (ex.: aviso de soma por projeto)
     */
    public record Card(boolean available, Double value, Double previous, Double change, String label, String note,
                       BlockError error) {
        static Card unavailable(String label, String note) {
            return new Card(false, null, null, null, label, note, null);
        }

        static Card failed(String label, VercelException e) {
            return new Card(true, null, null, null, label, null, BlockError.of(e));
        }
    }

    public record Cards(Card pageViews, Card events, Card visitors) {
    }

    public record ProjectRow(String id, String name, String url, double pageViews, Double change, Double visitors,
                             boolean hasData) {
    }

    public record Line(String id, String name, List<Double> values) {
    }

    /** @param openBucketIndex índice do bucket em andamento, ou {@code null} */
    public record Chart(List<Instant> buckets, Integer openBucketIndex, List<Line> lines) {
    }

    public record Overview(String scope, RangeInfo range, boolean production, Cards cards, List<ProjectRow> projects,
                           Chart chart, List<String> warnings, Instant fetchedAt, boolean stale) {
    }

    public record BreakdownRow(String value, double count) {
    }

    public record Breakdown(String projectId, String dimension, String vercelDimension, String metric, RangeInfo range,
                            int limit, List<BreakdownRow> rows, boolean hasData, String note, Instant fetchedAt,
                            boolean stale) {
    }

    /** @param source {@code vercel} na Fase 1; {@code local} quando vier do histórico */
    public record Bucket(Instant t, double value, String source, boolean inProgress) {
    }

    /** @param total soma dos buckets para métricas aditivas; para únicos, valor do período inteiro vindo da Vercel */
    public record Timeseries(String projectId, String metric, String aggClass, RangeInfo range, List<Bucket> buckets,
                             Double total, String totalNote, boolean hasData, Instant fetchedAt, boolean stale) {
    }
}
