package dev.overlook.vercel.parse;

import com.fasterxml.jackson.databind.JsonNode;
import dev.overlook.vercel.error.UnexpectedOutput;
import dev.overlook.vercel.model.MetricSchema;
import dev.overlook.vercel.model.SchemaInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Converte a saída de {@code vercel metrics schema <prefixo> --format json}: uma lista de
 * {@code {id, description, unit, dimensions, aggregations, defaultAggregation?}}, em que cada
 * dimensão pode ser uma string ou um objeto com {@code name}.
 */
public final class SchemaParser {

    private static final String WHAT = "vercel metrics schema";

    public SchemaInfo parse(String text) {
        JsonNode root = Json.read(text, WHAT);
        JsonNode list = root.isArray() ? root : root.get("metrics");
        if (list == null || !list.isArray()) {
            throw new UnexpectedOutput(WHAT + ": esperada lista de métricas", null);
        }
        List<MetricSchema> metrics = new ArrayList<>();
        for (JsonNode m : list) {
            String id = Json.required(m, "id", WHAT).asText();
            List<String> dimensions = new ArrayList<>();
            JsonNode dims = m.get("dimensions");
            if (dims != null && dims.isArray()) {
                for (JsonNode d : dims) {
                    String name = d.isTextual() ? d.asText() : Json.text(d, "name");
                    if (name != null && !name.isBlank()) {
                        dimensions.add(name);
                    }
                }
            }
            List<String> aggregations = new ArrayList<>();
            JsonNode aggs = m.get("aggregations");
            if (aggs != null && aggs.isArray()) {
                aggs.forEach(a -> aggregations.add(a.asText()));
            }
            metrics.add(new MetricSchema(id, Json.text(m, "description"), Json.text(m, "unit"),
                    dimensions, aggregations, Json.text(m, "defaultAggregation")));
        }
        return new SchemaInfo(metrics);
    }
}
