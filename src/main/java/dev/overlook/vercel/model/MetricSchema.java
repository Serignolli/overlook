package dev.overlook.vercel.model;

import java.util.List;

/** Uma métrica como descrita por {@code vercel metrics schema --format json}. */
public record MetricSchema(
        String id,
        String description,
        String unit,
        List<String> dimensions,
        List<String> aggregations,
        String defaultAggregation) {

    public MetricSchema {
        dimensions = List.copyOf(dimensions);
        aggregations = List.copyOf(aggregations);
    }

    public boolean hasDimension(String name) {
        return dimensions.contains(name);
    }

    public boolean supportsAggregation(String name) {
        return aggregations.contains(name);
    }
}
