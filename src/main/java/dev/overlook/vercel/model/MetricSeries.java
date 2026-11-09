package dev.overlook.vercel.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Resultado de uma consulta: {@code summary} traz um valor por grupo para o período inteiro,
 * {@code data} traz um valor por bucket e grupo.
 */
public record MetricSeries(
        String metric,
        String aggregation,
        List<String> groupBy,
        Instant start,
        Instant end,
        List<Row> summary,
        List<Point> data) {

    public MetricSeries {
        groupBy = List.copyOf(groupBy);
        summary = List.copyOf(summary);
        data = List.copyOf(data);
    }

    public record Row(Map<String, String> dimensions, double value) {
        public Row {
            dimensions = Map.copyOf(dimensions);
        }

        public String dimension(String name) {
            return dimensions.getOrDefault(name, "");
        }
    }

    public record Point(Instant timestamp, Map<String, String> dimensions, double value) {
        public Point {
            dimensions = Map.copyOf(dimensions);
        }

        public String dimension(String name) {
            return dimensions.getOrDefault(name, "");
        }
    }

    public boolean isEmpty() {
        return summary.isEmpty() && data.isEmpty();
    }
}
