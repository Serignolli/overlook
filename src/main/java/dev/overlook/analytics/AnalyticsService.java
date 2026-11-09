package dev.overlook.analytics;

import dev.overlook.analytics.Results.Breakdown;
import dev.overlook.analytics.Results.BreakdownRow;
import dev.overlook.analytics.Results.Bucket;
import dev.overlook.analytics.Results.Card;
import dev.overlook.analytics.Results.Cards;
import dev.overlook.analytics.Results.Chart;
import dev.overlook.analytics.Results.Line;
import dev.overlook.analytics.Results.Overview;
import dev.overlook.analytics.Results.ProjectRow;
import dev.overlook.analytics.Results.RangeInfo;
import dev.overlook.analytics.Results.Timeseries;
import dev.overlook.vercel.VercelGateway;
import dev.overlook.vercel.error.AnalyticsDisabled;
import dev.overlook.vercel.error.RateLimited;
import dev.overlook.vercel.error.VercelException;
import dev.overlook.vercel.model.CliHealth;
import dev.overlook.vercel.model.MetricQuery;
import dev.overlook.vercel.model.MetricSeries;
import dev.overlook.vercel.model.Project;
import dev.overlook.vercel.model.Scope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/** Monta as consultas, aplica as regras de agregação e entrega o que cada tela precisa. */
public final class AnalyticsService implements AutoCloseable {

    public static final int CHART_TOP_PROJECTS = 8;
    public static final String OTHERS_ID = "_others";
    public static final int DEFAULT_BREAKDOWN_LIMIT = 20;
    public static final int MAX_BREAKDOWN_LIMIT = 50;

    private static final Duration LIST_TTL = Duration.ofMinutes(5);
    private static final Duration SCHEMA_TTL = Duration.ofHours(1);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private final VercelGateway gateway;
    private final Clock clock;
    private final QueryCache cache;
    private final RateLimitBackoff backoff;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AnalyticsService(VercelGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
        this.cache = new QueryCache(clock);
        this.backoff = new RateLimitBackoff(clock);
    }

    public Clock clock() {
        return clock;
    }

    public CliHealth health() {
        return gateway.health();
    }

    public List<Scope> scopes() {
        return guarded(() -> cache.get("scopes", LIST_TTL, gateway::listScopes)).value();
    }

    /** Resolve o escopo pedido ({@code personal}, slug ou vazio) para o slug que a CLI aceita. */
    public String resolveScope(String requested, String fallback) {
        String wanted = requested == null || requested.isBlank() ? fallback : requested;
        List<Scope> scopes = scopes();
        if (wanted == null || wanted.isBlank() || wanted.equals("personal")) {
            return scopes.stream().filter(Scope::personal).findFirst()
                    .or(() -> scopes.stream().filter(Scope::current).findFirst())
                    .or(() -> scopes.stream().findFirst())
                    .map(Scope::slug)
                    .orElseThrow(() -> new IllegalArgumentException("Nenhum escopo disponível"));
        }
        requireSafe(wanted, "scope");
        return scopes.stream()
                .filter(s -> s.slug().equals(wanted) || s.id().equals(wanted))
                .findFirst()
                .map(Scope::slug)
                .orElseThrow(() -> new IllegalArgumentException("Escopo desconhecido: " + wanted));
    }

    public List<Project> projects(String scope) {
        return guarded(() -> cache.get("projects|" + scope, LIST_TTL, () -> gateway.listProjects(scope))).value();
    }

    public Capabilities capabilities(String scope) {
        return guarded(() -> cache.get("schema|" + scope, SCHEMA_TTL, () -> Capabilities.from(gateway.schema(scope))))
                .value();
    }

    public void refresh() {
        cache.invalidateAll();
        backoff.reset();
    }

    // ---------------------------------------------------------------- tela consolidada

    public Overview overview(String scope, TimeRange range, boolean production) {
        List<Project> projects = projects(scope);
        Capabilities caps = capabilities(scope);
        TimeRange previous = range.previous();
        int limit = Math.max(10, Math.min(100, projects.size()));
        List<String> byProject = List.of(caps.projectDimension());

        Future<Cached<MetricSeries>> pvCur = submit(query(scope, caps.pageViewMetric(), null, byProject, range, production, null, limit));
        Future<Cached<MetricSeries>> pvPrev = submit(query(scope, caps.pageViewMetric(), null, byProject, previous, production, null, limit));
        Future<Cached<MetricSeries>> evCur = caps.events()
                ? submit(query(scope, caps.eventMetric(), null, List.of(), range, production, null, null)) : null;
        Future<Cached<MetricSeries>> evPrev = caps.events()
                ? submit(query(scope, caps.eventMetric(), null, List.of(), previous, production, null, null)) : null;
        // únicos: só buckets fechados, para a comparação ser justa; cada período é uma consulta inteira
        TimeRange closed = range.closedOnly();
        TimeRange closedPrev = new TimeRange(range.key(), previous.since(),
                previous.since().plus(Duration.between(closed.since(), closed.until())), range.granularity(), range.now());
        boolean visitorsQueryable = caps.visitors() && closed.until().isAfter(closed.since());
        Future<Cached<MetricSeries>> visCur = visitorsQueryable
                ? submit(query(scope, caps.pageViewMetric(), Capabilities.VISITORS_AGGREGATION, byProject, closed, production, null, limit)) : null;
        Future<Cached<MetricSeries>> visPrev = visitorsQueryable
                ? submit(query(scope, caps.pageViewMetric(), Capabilities.VISITORS_AGGREGATION, byProject, closedPrev, production, null, limit)) : null;

        List<String> warnings = new ArrayList<>();
        List<Cached<?>> used = new ArrayList<>();

        Cached<MetricSeries> pv = await(pvCur);
        Cached<MetricSeries> pvP = await(pvPrev);
        used.add(pv);
        used.add(pvP);

        List<Instant> buckets = range.buckets();
        int closedCount = range.closedBuckets();
        String projDim = caps.projectDimension();
        Map<String, double[]> current = perProjectBuckets(pv.value(), projDim, buckets);
        Map<String, double[]> prior = perProjectBuckets(pvP.value(), projDim, previous.buckets());

        // cartão de page views
        double pvTotal = sumAll(current, buckets.size());
        double pvClosed = sumAll(current, closedCount);
        double pvPrevTotal = sumAll(prior, prior.isEmpty() ? 0 : previous.buckets().size());
        double pvPrevComparable = sumAll(prior, closedCount);
        Card pvCard = new Card(true, pvTotal, pvPrevTotal, AggregationRules.change(pvClosed, pvPrevComparable),
                "Page views", null, null);

        // cartão de eventos
        Card evCard;
        if (!caps.events()) {
            evCard = Card.unavailable("Eventos", "O schema não lista eventos customizados neste escopo.");
        } else {
            try {
                Cached<MetricSeries> ev = await(evCur);
                Cached<MetricSeries> evP = await(evPrev);
                used.add(ev);
                used.add(evP);
                double[] e = totalBuckets(ev.value(), buckets);
                double[] p = totalBuckets(evP.value(), previous.buckets());
                evCard = new Card(true, sum(e, e.length), sum(p, p.length),
                        AggregationRules.change(sum(e, closedCount), sum(p, closedCount)), "Eventos", null, null);
            } catch (VercelException ex) {
                evCard = Card.failed("Eventos", ex);
            }
        }

        // cartão de visitantes: soma por projeto, nunca "únicos totais"
        Card visCard;
        Map<String, Double> visitorsByProject = Map.of();
        if (!caps.visitors()) {
            visCard = Card.unavailable("Visitantes", "O schema não oferece contagem de visitantes únicos.");
        } else if (!visitorsQueryable) {
            visCard = Card.unavailable(AggregationRules.PROJECT_SUM_LABEL, "Ainda não há período fechado para comparar.");
        } else {
            try {
                Cached<MetricSeries> vis = await(visCur);
                Cached<MetricSeries> visP = await(visPrev);
                used.add(vis);
                used.add(visP);
                visitorsByProject = summaryByDimension(vis.value(), projDim);
                Map<String, Double> prevVisitors = summaryByDimension(visP.value(), projDim);
                AggregationRules.LabeledSum cur = AggregationRules.projectSumOfUniques(visitorsByProject.values());
                AggregationRules.LabeledSum prev = AggregationRules.projectSumOfUniques(prevVisitors.values());
                String note = cur.caveat() + (closedCount < buckets.size() ? " Considera só o período fechado." : "");
                visCard = new Card(true, cur.value(), prev.value(), AggregationRules.change(cur.value(), prev.value()),
                        cur.label(), note, null);
            } catch (VercelException ex) {
                visCard = Card.failed("Visitantes", ex);
            }
        }

        // ranking por projeto
        Map<String, Project> known = new LinkedHashMap<>();
        projects.forEach(p -> known.put(p.id(), p));
        List<ProjectRow> rows = new ArrayList<>();
        for (Project p : projects) {
            rows.add(projectRow(p.id(), p.name(), p.productionUrl(), current, prior, closedCount, buckets.size(),
                    visitorsByProject));
        }
        for (String id : current.keySet()) {
            if (!known.containsKey(id)) {
                rows.add(projectRow(id, id, null, current, prior, closedCount, buckets.size(), visitorsByProject));
            }
        }
        rows.sort(Comparator.comparingDouble(ProjectRow::pageViews).reversed().thenComparing(ProjectRow::name));
        if (current.size() >= limit) {
            warnings.add("Mostrando os " + limit + " projetos com mais page views.");
        }

        // gráfico: top 8 + outros
        List<Line> lines = new ArrayList<>();
        double[] others = new double[buckets.size()];
        boolean hasOthers = false;
        int shown = 0;
        for (ProjectRow row : rows) {
            double[] values = current.get(row.id());
            if (values == null) {
                continue;
            }
            if (shown < CHART_TOP_PROJECTS) {
                lines.add(new Line(row.id(), row.name(), boxed(values)));
                shown++;
            } else {
                hasOthers = true;
                for (int i = 0; i < values.length; i++) {
                    others[i] += values[i];
                }
            }
        }
        if (hasOthers) {
            lines.add(new Line(OTHERS_ID, "Outros", boxed(others)));
        }
        Integer openIndex = closedCount < buckets.size() ? closedCount : null;

        boolean stale = used.stream().anyMatch(Cached::stale);
        if (stale) {
            warnings.add("Limite de requisições da Vercel: mostrando dados do cache.");
        }
        Instant fetchedAt = used.stream().map(Cached::fetchedAt).min(Comparator.naturalOrder()).orElse(clock.instant());
        return new Overview(scope, RangeInfo.of(range), production, new Cards(pvCard, evCard, visCard), rows,
                new Chart(buckets, openIndex, lines), warnings, fetchedAt, stale);
    }

    private static ProjectRow projectRow(String id, String name, String url, Map<String, double[]> current,
                                         Map<String, double[]> prior, int closedCount, int bucketCount,
                                         Map<String, Double> visitors) {
        double[] cur = current.get(id);
        double[] prev = prior.get(id);
        double total = cur == null ? 0 : sum(cur, bucketCount);
        Double change = cur == null || prev == null ? null
                : AggregationRules.change(sum(cur, closedCount), sum(prev, closedCount));
        return new ProjectRow(id, name, url, total, change, visitors.get(id), cur != null && total > 0);
    }

    // ---------------------------------------------------------------- tela de projeto

    public Breakdown breakdown(String scope, String projectId, String dimensionKey, TimeRange range, boolean production,
                               Integer limit) {
        requireSafe(projectId, "projectId");
        Capabilities caps = capabilities(scope);
        String dim = caps.dimensions().get(dimensionKey);
        if (dim == null) {
            throw new IllegalArgumentException("Dimensão não disponível neste escopo: " + dimensionKey);
        }
        String metric = caps.isEventDimension(dimensionKey) ? caps.eventMetric() : caps.pageViewMetric();
        int n = limit == null ? DEFAULT_BREAKDOWN_LIMIT : Math.max(1, Math.min(MAX_BREAKDOWN_LIMIT, limit));
        Cached<MetricSeries> result = fetch(query(scope, metric, null, List.of(dim), range, production, projectId, n));
        List<BreakdownRow> rows = result.value().summary().stream()
                .map(r -> new BreakdownRow(r.dimension(dim), r.value()))
                .filter(r -> r.count() > 0)
                .sorted(Comparator.comparingDouble(BreakdownRow::count).reversed())
                .limit(n)
                .toList();
        boolean hasData = !rows.isEmpty();
        String note = hasData ? "Top " + n + " por " + (caps.isEventDimension(dimensionKey) ? "eventos" : "page views") + "."
                : caps.isEventDimension(dimensionKey) ? "Nenhum evento customizado no período."
                : new AnalyticsDisabled(projectId).action();
        return new Breakdown(projectId, dimensionKey, dim, metric, RangeInfo.of(range), n, rows, hasData, note,
                result.fetchedAt(), result.stale());
    }

    public Timeseries timeseries(String scope, String projectId, String metricKey, TimeRange range, boolean production) {
        requireSafe(projectId, "projectId");
        Capabilities caps = capabilities(scope);
        String metric;
        String aggregation = null;
        switch (metricKey == null ? "pageViews" : metricKey) {
            case "pageViews" -> metric = caps.pageViewMetric();
            case "events" -> {
                if (!caps.events()) {
                    throw new IllegalArgumentException("Eventos não disponíveis neste escopo");
                }
                metric = caps.eventMetric();
            }
            case "visitors" -> {
                if (!caps.visitors()) {
                    throw new IllegalArgumentException("Visitantes não disponíveis neste escopo");
                }
                metric = caps.pageViewMetric();
                aggregation = Capabilities.VISITORS_AGGREGATION;
            }
            default -> throw new IllegalArgumentException("metric deve ser pageViews, events ou visitors");
        }
        AggClass aggClass = AggregationRules.classOf(aggregation);
        Cached<MetricSeries> result = fetch(query(scope, metric, aggregation, List.of(), range, production, projectId, null));
        List<Instant> times = range.buckets();
        double[] values = totalBuckets(result.value(), times);
        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            buckets.add(new Bucket(times.get(i), values[i], "vercel", range.isOpen(times.get(i))));
        }
        Double total;
        String totalNote = null;
        if (AggregationRules.summableAcrossBuckets(aggClass)) {
            total = AggregationRules.periodTotal(aggClass, boxed(values)).orElse(0);
        } else {
            // únicos do período inteiro, como a Vercel calculou; nunca a soma dos dias
            total = result.value().summary().stream().mapToDouble(MetricSeries.Row::value).sum();
            totalNote = "Visitantes únicos do período, calculados pela Vercel. A série mostra os únicos de cada bucket.";
        }
        boolean hasData = !result.value().isEmpty() && (total > 0 || sum(values, values.length) > 0);
        return new Timeseries(projectId, metricKey, aggClass.name(), RangeInfo.of(range), buckets, total, totalNote,
                hasData, result.fetchedAt(), result.stale());
    }

    // ---------------------------------------------------------------- consultas

    private MetricQuery query(String scope, String metric, String aggregation, List<String> groupBy, TimeRange range,
                              boolean production, String projectId, Integer limit) {
        return new MetricQuery(scope, metric, aggregation, groupBy, range.since(), range.until(), range.granularity(),
                production, projectId, groupBy.isEmpty() ? null : limit);
    }

    private Future<Cached<MetricSeries>> submit(MetricQuery q) {
        return executor.submit(() -> fetch(q));
    }

    private Cached<MetricSeries> fetch(MetricQuery q) {
        return guarded(() -> cache.get(q.cacheKey(), QueryCache.DEFAULT_TTL, () -> gateway.query(q)));
    }

    private <T> Cached<T> guarded(java.util.function.Supplier<Cached<T>> call) {
        try {
            Cached<T> result = call.get();
            if (!result.stale()) {
                backoff.reset();
            }
            return result;
        } catch (RateLimited e) {
            throw e.withRetryAfter(backoff.onRateLimited());
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Consulta interrompida", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException ce ? ce.getCause() : e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }

    // ---------------------------------------------------------------- séries

    static Map<String, double[]> perProjectBuckets(MetricSeries series, String dim, List<Instant> buckets) {
        Map<Instant, Integer> index = indexOf(buckets);
        Map<String, double[]> out = new LinkedHashMap<>();
        for (MetricSeries.Point p : series.data()) {
            Integer i = index.get(p.timestamp());
            if (i == null) {
                continue;
            }
            out.computeIfAbsent(p.dimension(dim), k -> new double[buckets.size()])[i] += p.value();
        }
        if (series.data().isEmpty()) {
            // sem série temporal: usa o resumo inteiro no primeiro bucket para não perder o total
            for (MetricSeries.Row r : series.summary()) {
                if (r.value() > 0 && !buckets.isEmpty()) {
                    out.computeIfAbsent(r.dimension(dim), k -> new double[buckets.size()])[0] += r.value();
                }
            }
        }
        return out;
    }

    static double[] totalBuckets(MetricSeries series, List<Instant> buckets) {
        Map<Instant, Integer> index = indexOf(buckets);
        double[] out = new double[buckets.size()];
        for (MetricSeries.Point p : series.data()) {
            Integer i = index.get(p.timestamp());
            if (i != null) {
                out[i] += p.value();
            }
        }
        return out;
    }

    private static Map<String, Double> summaryByDimension(MetricSeries series, String dim) {
        Map<String, Double> out = new HashMap<>();
        series.summary().forEach(r -> out.merge(r.dimension(dim), r.value(), Double::sum));
        return out;
    }

    private static Map<Instant, Integer> indexOf(List<Instant> buckets) {
        Map<Instant, Integer> index = new HashMap<>();
        for (int i = 0; i < buckets.size(); i++) {
            index.put(buckets.get(i), i);
        }
        return index;
    }

    private static double sumAll(Map<String, double[]> perProject, int firstN) {
        return perProject.values().stream().mapToDouble(v -> sum(v, firstN)).sum();
    }

    private static double sum(double[] values, int firstN) {
        double total = 0;
        for (int i = 0; i < Math.min(firstN, values.length); i++) {
            total += values[i];
        }
        return total;
    }

    private static List<Double> boxed(double[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (double v : values) {
            list.add(v);
        }
        return list;
    }

    private static void requireSafe(String value, String name) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " inválido");
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
