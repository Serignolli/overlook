package dev.overlook.analytics;

import dev.overlook.vercel.error.CliFailure;
import dev.overlook.vercel.model.MetricSchema;
import dev.overlook.vercel.model.SchemaInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * O que o {@code schema} da Vercel oferece neste escopo. Nada aqui é fixo no código além das
 * listas de candidatos: o app usa o primeiro nome que o schema listar.
 *
 * @param dimensions chave da UI ({@code pages}, {@code countries}...) para o nome da dimensão na Vercel
 */
public record Capabilities(
        String pageViewMetric,
        String eventMetric,
        boolean visitors,
        String projectDimension,
        Map<String, String> dimensions) {

    static final List<String> PAGE_VIEW_CANDIDATES =
            List.of("vercel.analytics.page_view.count", "vercel.analytics_pageview.count");
    static final List<String> EVENT_CANDIDATES =
            List.of("vercel.analytics.event.count", "vercel.analytics_event.count");
    static final List<String> PROJECT_DIMENSION_CANDIDATES = List.of("projectId", "project_id");
    static final String VISITOR_DIMENSION = "visitorId";
    public static final String VISITORS_AGGREGATION = "unique/" + VISITOR_DIMENSION;

    /** Chave da UI, se a dimensão é de eventos, e nomes possíveis na Vercel. */
    private record DimensionSpec(boolean onEvents, List<String> candidates) {
    }

    private static final Map<String, DimensionSpec> DIMENSIONS = new LinkedHashMap<>();

    static {
        DIMENSIONS.put("pages", new DimensionSpec(false, List.of("requestPath", "path", "route")));
        DIMENSIONS.put("referrers", new DimensionSpec(false, List.of("referrerHostname", "referrer", "referer")));
        DIMENSIONS.put("countries", new DimensionSpec(false, List.of("country")));
        DIMENSIONS.put("devices", new DimensionSpec(false, List.of("deviceType", "device")));
        DIMENSIONS.put("events", new DimensionSpec(true, List.of("eventName")));
    }

    public static Capabilities from(SchemaInfo schema) {
        MetricSchema pageViews = schema.findFirst(PAGE_VIEW_CANDIDATES)
                .or(() -> schema.metrics().stream()
                        .filter(m -> m.id().contains("page_view") || m.id().contains("pageview"))
                        .findFirst())
                .orElseThrow(() -> new CliFailure("ANALYTICS_UNAVAILABLE",
                        "O schema da Vercel não lista métricas de Web Analytics neste escopo."));
        Optional<MetricSchema> events = schema.findFirst(EVENT_CANDIDATES)
                .or(() -> schema.metrics().stream()
                        .filter(m -> m.id().contains("analytics") && m.id().contains("event"))
                        .findFirst());

        boolean visitors = pageViews.supportsAggregation("unique") && pageViews.hasDimension(VISITOR_DIMENSION);
        String projectDim = PROJECT_DIMENSION_CANDIDATES.stream()
                .filter(pageViews::hasDimension)
                .findFirst()
                .orElse("projectId");

        Map<String, String> dims = new LinkedHashMap<>();
        DIMENSIONS.forEach((key, spec) -> {
            Optional<MetricSchema> metric = spec.onEvents() ? events : Optional.of(pageViews);
            metric.flatMap(m -> spec.candidates().stream().filter(m::hasDimension).findFirst())
                    .ifPresent(name -> dims.put(key, name));
        });
        return new Capabilities(pageViews.id(), events.map(MetricSchema::id).orElse(null), visitors, projectDim, dims);
    }

    public boolean events() {
        return eventMetric != null;
    }

    public boolean isEventDimension(String key) {
        DimensionSpec spec = DIMENSIONS.get(key);
        return spec != null && spec.onEvents();
    }
}
