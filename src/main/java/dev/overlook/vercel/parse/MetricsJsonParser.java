package dev.overlook.vercel.parse;

import com.fasterxml.jackson.databind.JsonNode;
import dev.overlook.vercel.error.UnexpectedOutput;
import dev.overlook.vercel.model.MetricSeries;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Converte a saída de {@code vercel metrics <metric> --format json}:
 *
 * <pre>
 * {
 *   "query":   { "metric", "aggregation", "groupBy": [...], "startTime", "endTime", ... },
 *   "summary": [ { "&lt;dim&gt;": "...", "&lt;metric&gt;_&lt;agg&gt;": 123 } ],
 *   "data":    [ { "timestamp": "...", "&lt;dim&gt;": "...", "&lt;metric&gt;_&lt;agg&gt;": 12 } ]
 * }
 * </pre>
 *
 * A coluna de valor se chama {@code <metric>_<aggregation>} com {@code .} e {@code /} trocados
 * por {@code _}. Se ela não existir, o parser aceita a única coluna numérica restante.
 */
public final class MetricsJsonParser {

    private static final String WHAT = "vercel metrics";

    public MetricSeries parse(String text) {
        JsonNode root = Json.read(text, WHAT);
        if (!root.isObject()) {
            throw new UnexpectedOutput(WHAT + ": esperado objeto JSON", null);
        }
        JsonNode query = Json.required(root, "query", WHAT);
        String metric = Json.required(query, "metric", WHAT).asText();
        String aggregation = Json.text(query, "aggregation");
        List<String> groupBy = new ArrayList<>();
        JsonNode groupNode = query.get("groupBy");
        if (groupNode != null && groupNode.isArray()) {
            groupNode.forEach(n -> groupBy.add(n.asText()));
        }
        Instant start = parseInstantOrNull(query.get("startTime"));
        Instant end = parseInstantOrNull(query.get("endTime"));
        String valueColumn = rollupColumnName(metric, aggregation);

        JsonNode summaryNode = requireArray(root, "summary");
        JsonNode dataNode = requireArray(root, "data");

        List<MetricSeries.Row> summary = new ArrayList<>();
        for (JsonNode row : summaryNode) {
            summary.add(new MetricSeries.Row(dimensions(row, groupBy), value(row, valueColumn, groupBy)));
        }
        List<MetricSeries.Point> data = new ArrayList<>();
        for (JsonNode row : dataNode) {
            JsonNode ts = row.get("timestamp");
            if (ts == null || ts.isNull()) {
                throw new UnexpectedOutput(WHAT + ": ponto sem 'timestamp'", null);
            }
            data.add(new MetricSeries.Point(parseInstant(ts), dimensions(row, groupBy), value(row, valueColumn, groupBy)));
        }
        return new MetricSeries(metric, aggregation, groupBy, start, end, summary, data);
    }

    public static String rollupColumnName(String metric, String aggregation) {
        return (metric + "_" + aggregation).replaceAll("[./]", "_");
    }

    private static JsonNode requireArray(JsonNode root, String field) {
        JsonNode node = Json.required(root, field, WHAT);
        if (!node.isArray()) {
            throw new UnexpectedOutput(WHAT + ": '" + field + "' não é uma lista", null);
        }
        return node;
    }

    private static Map<String, String> dimensions(JsonNode row, List<String> groupBy) {
        Map<String, String> dims = new HashMap<>();
        for (String dim : groupBy) {
            JsonNode v = row.get(dim);
            dims.put(dim, v == null || v.isNull() ? "" : v.asText());
        }
        return dims;
    }

    private static double value(JsonNode row, String valueColumn, List<String> groupBy) {
        JsonNode v = row.get(valueColumn);
        if (v == null) {
            v = soleNumericColumn(row, groupBy);
        }
        if (v == null || v.isNull()) {
            return 0d;
        }
        if (!v.isNumber()) {
            try {
                return Double.parseDouble(v.asText());
            } catch (NumberFormatException e) {
                throw new UnexpectedOutput(WHAT + ": valor não numérico", e);
            }
        }
        return v.asDouble();
    }

    private static JsonNode soleNumericColumn(JsonNode row, List<String> groupBy) {
        JsonNode found = null;
        Iterator<Map.Entry<String, JsonNode>> it = row.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equals("timestamp") || groupBy.contains(e.getKey())) {
                continue;
            }
            if (e.getValue().isNumber() || e.getValue().isNull()) {
                if (found != null) {
                    throw new UnexpectedOutput(WHAT + ": coluna de valor ambígua", null);
                }
                found = e.getValue();
            }
        }
        if (found == null) {
            throw new UnexpectedOutput(WHAT + ": linha sem coluna de valor", null);
        }
        return found;
    }

    private static Instant parseInstantOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : parseInstant(node);
    }

    static Instant parseInstant(JsonNode node) {
        if (node.isNumber()) {
            long n = node.asLong();
            // segundos ou milissegundos
            return n < 100_000_000_000L ? Instant.ofEpochSecond(n) : Instant.ofEpochMilli(n);
        }
        String s = node.asText();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                throw new UnexpectedOutput(WHAT + ": data inválida '" + s + "'", e2);
            }
        }
    }
}
