package dev.overlook.vercel.model;

import java.util.List;
import java.util.Optional;

public record SchemaInfo(List<MetricSchema> metrics) {

    public SchemaInfo {
        metrics = List.copyOf(metrics);
    }

    public Optional<MetricSchema> find(String metricId) {
        return metrics.stream().filter(m -> m.id().equals(metricId)).findFirst();
    }

    /** Primeira métrica cujo ID está na lista de candidatos, na ordem dos candidatos. */
    public Optional<MetricSchema> findFirst(List<String> candidates) {
        for (String candidate : candidates) {
            Optional<MetricSchema> found = find(candidate);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
