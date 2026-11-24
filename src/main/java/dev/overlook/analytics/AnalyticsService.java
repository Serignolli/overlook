package dev.overlook.analytics;

import dev.overlook.analytics.Results.Breakdown;
import dev.overlook.analytics.Results.BreakdownRow;
import dev.overlook.analytics.Results.Bucket;
import dev.overlook.analytics.Results.Card;
import dev.overlook.analytics.Results.Cards;
import dev.overlook.analytics.Results.Chart;
import dev.overlook.analytics.Results.Limits;
import dev.overlook.analytics.Results.Line;
import dev.overlook.analytics.Results.Overview;
import dev.overlook.analytics.Results.ProjectRow;
import dev.overlook.analytics.Results.RangeInfo;
import dev.overlook.analytics.Results.Timeseries;
import dev.overlook.history.HistoryStore;
import dev.overlook.history.HistoryStore.SeriesKey;
import dev.overlook.history.HistoryStore.Slice;
import dev.overlook.vercel.VercelGateway;
import dev.overlook.vercel.error.AnalyticsDisabled;
import dev.overlook.vercel.error.PlanRequired;
import dev.overlook.vercel.error.RateLimited;
import dev.overlook.vercel.error.RetentionLimit;
import dev.overlook.vercel.error.VercelException;
import dev.overlook.vercel.model.CliHealth;
import dev.overlook.vercel.model.MetricQuery;
import dev.overlook.vercel.model.MetricSeries;
import dev.overlook.vercel.model.Project;
import dev.overlook.vercel.model.Scope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Janela de relatório por plano, segundo vercel.com/docs/analytics/limits-and-pricing
     * (atualizada em 25/08/2026): Hobby 1 mês, Pro 12 meses, Enterprise 24 meses. Pro com Web
     * Analytics Plus também tem 24 meses, mas a CLI não informa o add-on; fica nos 12 de um Pro
     * comum. O limite que a própria Vercel devolver em erro tem prioridade.
     */
    static final Map<String, Integer> PLAN_RETENTION_DAYS = Map.of("hobby", 31, "pro", 365, "enterprise", 730);

    private static final Duration LIST_TTL = Duration.ofMinutes(5);
    private static final Duration SCHEMA_TTL = Duration.ofHours(1);
    /** Consultas que terminam antes disto já estão fechadas e não mudam mais. */
    private static final Duration CLOSED_AFTER = Duration.ofHours(1);
    private static final Duration CLOSED_TTL = Duration.ofHours(1);
    /** Só vão para o histórico buckets encerrados há pelo menos isto (a Vercel pode atrasar dados). */
    private static final Duration SETTLE = Duration.ofHours(1);
    static final String EVENTS_PLAN_NOTE = "Eventos customizados exigem plano Pro ou Enterprise.";
    private static final String TOTAL_SERIES = "_total";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);

    private final VercelGateway gateway;
    private final Clock clock;
    private final HistoryStore history;
    private final QueryCache cache;
    private final RateLimitBackoff backoff;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** Dias de histórico que o plano libera, aprendidos com as respostas da Vercel, por escopo. */
    private final Map<String, Integer> learnedRetention = new ConcurrentHashMap<>();
    /** Escopos em que a Vercel recusou eventos customizados por causa do plano. */
    private final Set<String> eventsBlocked = ConcurrentHashMap.newKeySet();
    /** Última resposta já gravada no histórico, por chave de cache, para não regravar o mesmo dado. */
    private final Map<String, Instant> recorded = new ConcurrentHashMap<>();

    public AnalyticsService(VercelGateway gateway, Clock clock) {
        this(gateway, clock, HistoryStore.inMemory(() -> false));
    }

    public AnalyticsService(VercelGateway gateway, Clock clock, HistoryStore history) {
        this.gateway = gateway;
        this.clock = clock;
        this.history = history;
        this.cache = new QueryCache(clock);
        this.backoff = new RateLimitBackoff(clock);
    }

    public Clock clock() {
        return clock;
    }

    public HistoryStore history() {
        return history;
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

    /** O que dá para consultar neste escopo; sem eventos quando o plano já recusou. */
    public Capabilities capabilities(String scope) {
        Capabilities caps = schemaCapabilities(scope);
        return eventsBlocked.contains(scope) ? caps.withoutEvents() : caps;
    }

    private Capabilities schemaCapabilities(String scope) {
        return guarded(() -> cache.get("schema|" + scope, SCHEMA_TTL, () -> Capabilities.from(gateway.schema(scope))))
                .value();
    }

    public void refresh() {
        cache.invalidateAll();
        backoff.reset();
        learnedRetention.clear();
        eventsBlocked.clear();
        recorded.clear();
    }

    // ---------------------------------------------------------------- limites do plano

    public Limits limits(String scope) {
        Integer days = retentionDays(scope);
        return new Limits(scope, plan(scope), days, retentionStart(scope), history.enabled(), history.oldest(scope));
    }

    /** Apaga do histórico local o que for mais antigo que {@code months} meses; 0 mantém tudo. */
    public void pruneHistory(int months) {
        if (months > 0) {
            history.prune(clock.instant().atZone(ZoneOffset.UTC).minusMonths(months).toInstant());
        }
    }

    private String plan(String scope) {
        try {
            return scopes().stream().filter(s -> s.slug().equals(scope)).map(Scope::plan).filter(Objects::nonNull)
                    .findFirst().orElse(null);
        } catch (VercelException e) {
            return null;
        }
    }

    /** O menor entre o limite do plano e o que a Vercel já respondeu; {@code null} se nenhum é conhecido. */
    Integer retentionDays(String scope) {
        String plan = plan(scope);
        Integer byPlan = plan == null ? null : PLAN_RETENTION_DAYS.get(plan.toLowerCase(Locale.ROOT));
        Integer learned = learnedRetention.get(scope);
        if (byPlan == null) {
            return learned;
        }
        return learned == null ? byPlan : Math.min(byPlan, learned);
    }

    /**
     * Primeiro instante que a Vercel ainda devolve. Conservador: começa no início do dia, um dia
     * depois do limite exato, para não depender de como a Vercel conta a janela.
     */
    private Instant retentionStart(String scope) {
        Integer days = retentionDays(scope);
        return days == null ? null
                : clock.instant().minus(Duration.ofDays(days - 1L)).truncatedTo(ChronoUnit.DAYS);
    }

    /** Falha sem chamar a CLI quando já se sabe que o plano não alcança o período (e não há histórico). */
    private void requireRetention(String scope, TimeRange range) {
        if (!history.enabled() && !withinRetention(scope, range.since())) {
            throw new RetentionLimit(retentionDays(scope));
        }
    }

    private boolean withinRetention(String scope, Instant since) {
        Instant start = retentionStart(scope);
        return start == null || !since.isBefore(start);
    }

    private String noComparisonWarning(String scope) {
        Integer days = retentionDays(scope);
        String base = "Sem comparação com o período anterior: o plano só dá acesso aos últimos "
                + (days == null ? "dias permitidos" : days + " dias") + " de Web Analytics";
        return history.enabled() ? base + " e o histórico local ainda não tem esse período."
                : base + ". Ative o histórico local nas configurações para guardar os dados daqui para a frente.";
    }

    private static String historyWarning(SeriesResult r) {
        String until = DAY.format(r.localUntil().minusSeconds(1));
        return r.complete()
                ? "Os dados até " + until + " vêm do histórico local."
                : "Os dados até " + until + " vêm do histórico local, que não cobre o período inteiro: "
                        + "dias sem dados guardados aparecem zerados.";
    }

    // ---------------------------------------------------------------- tela consolidada

    public Overview overview(String scope, TimeRange range, boolean production) {
        requireRetention(scope, range);
        List<Project> projects = projects(scope);
        Capabilities caps = capabilities(scope);
        TimeRange previous = range.previous();
        boolean prevAllowed = history.enabled() || withinRetention(scope, previous.since());
        int limit = Math.max(10, Math.min(100, projects.size()));
        List<String> byProject = List.of(caps.projectDimension());

        Future<SeriesResult> pvCur = submit(query(scope, caps.pageViewMetric(), null, byProject, range, production, null, limit));
        Future<SeriesResult> pvPrev = prevAllowed
                ? submit(query(scope, caps.pageViewMetric(), null, byProject, previous, production, null, limit)) : null;
        Future<SeriesResult> evCur = caps.events()
                ? submit(query(scope, caps.eventMetric(), null, List.of(), range, production, null, null)) : null;
        Future<SeriesResult> evPrev = caps.events() && prevAllowed
                ? submit(query(scope, caps.eventMetric(), null, List.of(), previous, production, null, null)) : null;
        // únicos: só buckets fechados, para a comparação ser justa; cada período é uma consulta inteira
        TimeRange closed = range.closedOnly();
        TimeRange closedPrev = new TimeRange(range.key(), previous.since(),
                previous.since().plus(Duration.between(closed.since(), closed.until())), range.granularity(), range.now());
        boolean visitorsQueryable = caps.visitors() && closed.until().isAfter(closed.since());
        Future<SeriesResult> visCur = visitorsQueryable
                ? submit(query(scope, caps.pageViewMetric(), Capabilities.VISITORS_AGGREGATION, byProject, closed, production, null, limit)) : null;
        Future<SeriesResult> visPrev = visitorsQueryable && prevAllowed
                ? submit(query(scope, caps.pageViewMetric(), Capabilities.VISITORS_AGGREGATION, byProject, closedPrev, production, null, limit)) : null;

        List<String> warnings = new ArrayList<>();
        List<Cached<?>> used = new ArrayList<>();

        SeriesResult pv = await(pvCur);
        used.add(pv.cached());
        if (pv.usedLocal()) {
            warnings.add(historyWarning(pv));
        }
        SeriesResult pvP = awaitPrevious(pvPrev);
        if (pvP != null) {
            used.add(pvP.cached());
        }
        boolean compared = pvP != null && pvP.complete();
        if (!compared) {
            warnings.add(pvP == null ? noComparisonWarning(scope)
                    : "Sem comparação com o período anterior: o histórico local não cobre esse período inteiro.");
        }

        List<Instant> buckets = range.buckets();
        int closedCount = range.closedBuckets();
        String projDim = caps.projectDimension();
        Map<String, double[]> current = perProjectBuckets(pv.value(), projDim, buckets);
        Map<String, double[]> prior = compared ? perProjectBuckets(pvP.value(), projDim, previous.buckets()) : Map.of();

        // cartão de page views
        double pvTotal = sumAll(current, buckets.size());
        double pvClosed = sumAll(current, closedCount);
        double pvPrevTotal = sumAll(prior, prior.isEmpty() ? 0 : previous.buckets().size());
        double pvPrevComparable = sumAll(prior, closedCount);
        Card pvCard = new Card(true, pvTotal, compared ? pvPrevTotal : null,
                compared ? AggregationRules.change(pvClosed, pvPrevComparable) : null, "Page views", null, null);

        // cartão de eventos
        Card evCard;
        if (eventsBlocked.contains(scope)) {
            evCard = Card.unavailable("Eventos", EVENTS_PLAN_NOTE);
        } else if (!caps.events()) {
            evCard = Card.unavailable("Eventos", "O schema não lista eventos customizados neste escopo.");
        } else {
            try {
                SeriesResult ev = await(evCur);
                used.add(ev.cached());
                double[] e = totalBuckets(ev.value(), buckets);
                SeriesResult evP = awaitPrevious(evPrev);
                if (evP == null || !evP.complete()) {
                    evCard = new Card(true, sum(e, e.length), null, null, "Eventos", null, null);
                } else {
                    used.add(evP.cached());
                    double[] p = totalBuckets(evP.value(), previous.buckets());
                    evCard = new Card(true, sum(e, e.length), sum(p, p.length),
                            AggregationRules.change(sum(e, closedCount), sum(p, closedCount)), "Eventos", null, null);
                }
            } catch (PlanRequired ex) {
                evCard = Card.unavailable("Eventos", EVENTS_PLAN_NOTE);
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
                SeriesResult vis = await(visCur);
                used.add(vis.cached());
                if (vis.usedLocal()) {
                    // únicos de cada dia não somam para o período: só a Vercel calcula o período inteiro
                    visCard = Card.unavailable(AggregationRules.PROJECT_SUM_LABEL,
                            "Visitantes únicos não dá para calcular com dados do histórico local. Escolha um período "
                                    + "dentro do que o plano libera.");
                } else {
                    visitorsByProject = summaryByDimension(vis.value(), projDim);
                    AggregationRules.LabeledSum cur = AggregationRules.projectSumOfUniques(visitorsByProject.values());
                    String note = cur.caveat() + (closedCount < buckets.size() ? " Considera só o período fechado." : "");
                    SeriesResult visP = awaitPrevious(visPrev);
                    if (visP == null || visP.usedLocal()) {
                        visCard = new Card(true, cur.value(), null, null, cur.label(), note, null);
                    } else {
                        used.add(visP.cached());
                        AggregationRules.LabeledSum prev =
                                AggregationRules.projectSumOfUniques(summaryByDimension(visP.value(), projDim).values());
                        visCard = new Card(true, cur.value(), prev.value(),
                                AggregationRules.change(cur.value(), prev.value()), cur.label(), note, null);
                    }
                }
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
        requireRetention(scope, range);
        Capabilities caps = capabilities(scope);
        String dim = caps.dimensions().get(dimensionKey);
        if (dim == null && caps.isEventDimension(dimensionKey) && eventsBlocked.contains(scope)) {
            throw new PlanRequired(EVENTS_PLAN_NOTE);
        }
        if (dim == null) {
            throw new IllegalArgumentException("Dimensão não disponível neste escopo: " + dimensionKey);
        }
        // detalhamentos são do período inteiro e não ficam no histórico: só o trecho que a Vercel alcança
        TimeRange effective = range;
        String clampNote = null;
        Instant start = retentionStart(scope);
        if (start != null && range.since().isBefore(start)) {
            Instant aligned = alignUp(range.since(), start, range.granularity().duration());
            if (!aligned.isBefore(range.until())) {
                throw new RetentionLimit(retentionDays(scope), history.enabled());
            }
            effective = new TimeRange(range.key(), aligned, range.until(), range.granularity(), range.now());
            clampNote = " Só a partir de " + DAY.format(aligned) + ": detalhamentos não ficam no histórico local.";
        }
        String metric = caps.isEventDimension(dimensionKey) ? caps.eventMetric() : caps.pageViewMetric();
        int n = limit == null ? DEFAULT_BREAKDOWN_LIMIT : Math.max(1, Math.min(MAX_BREAKDOWN_LIMIT, limit));
        Cached<MetricSeries> result = fetch(query(scope, metric, null, List.of(dim), effective, production, projectId, n));
        boolean dropBlank = Capabilities.isUtmDimension(dimensionKey);
        List<BreakdownRow> rows = result.value().summary().stream()
                .map(r -> new BreakdownRow(r.dimension(dim), r.value()))
                .filter(r -> r.count() > 0)
                .filter(r -> !dropBlank || !r.value().isBlank())
                .sorted(Comparator.comparingDouble(BreakdownRow::count).reversed())
                .limit(n)
                .toList();
        boolean hasData = !rows.isEmpty();
        String note = hasData ? "Top " + n + " por " + (caps.isEventDimension(dimensionKey) ? "eventos" : "page views") + "."
                : caps.isEventDimension(dimensionKey) ? "Nenhum evento customizado no período."
                : dropBlank ? "Nenhuma visita com UTM no período."
                : new AnalyticsDisabled(projectId).action();
        if (clampNote != null) {
            note += clampNote;
        }
        return new Breakdown(projectId, dimensionKey, dim, metric, RangeInfo.of(effective), n, rows, hasData, note,
                result.fetchedAt(), result.stale());
    }

    public Timeseries timeseries(String scope, String projectId, String metricKey, TimeRange range, boolean production) {
        requireSafe(projectId, "projectId");
        requireRetention(scope, range);
        Capabilities caps = capabilities(scope);
        String metric;
        String aggregation = null;
        switch (metricKey == null ? "pageViews" : metricKey) {
            case "pageViews" -> metric = caps.pageViewMetric();
            case "events" -> {
                if (eventsBlocked.contains(scope)) {
                    throw new PlanRequired(EVENTS_PLAN_NOTE);
                }
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
        SeriesResult result = series(query(scope, metric, aggregation, List.of(), range, production, projectId, null));
        List<Instant> times = range.buckets();
        double[] values = totalBuckets(result.value(), times);
        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            Instant t = times.get(i);
            String source = result.usedLocal() && t.isBefore(result.localUntil()) ? "local" : "vercel";
            buckets.add(new Bucket(t, values[i], source, range.isOpen(t)));
        }
        Double total;
        String totalNote = null;
        if (AggregationRules.summableAcrossBuckets(aggClass)) {
            total = AggregationRules.periodTotal(aggClass, boxed(values)).orElse(0);
        } else if (result.usedLocal()) {
            total = null;
            totalNote = "Visitantes únicos do período não dá para calcular com dados do histórico local. "
                    + "A série mostra os únicos de cada bucket.";
        } else {
            // únicos do período inteiro, como a Vercel calculou; nunca a soma dos dias
            total = result.value().summary().stream().mapToDouble(MetricSeries.Row::value).sum();
            totalNote = "Visitantes únicos do período, calculados pela Vercel. A série mostra os únicos de cada bucket.";
        }
        boolean hasData = !result.value().isEmpty() && ((total != null && total > 0) || sum(values, values.length) > 0);
        return new Timeseries(projectId, metricKey, aggClass.name(), RangeInfo.of(range), buckets, total, totalNote,
                hasData, result.cached().fetchedAt(), result.cached().stale(),
                result.usedLocal() ? historyWarning(result) : null);
    }

    // ---------------------------------------------------------------- consultas

    private MetricQuery query(String scope, String metric, String aggregation, List<String> groupBy, TimeRange range,
                              boolean production, String projectId, Integer limit) {
        return new MetricQuery(scope, metric, aggregation, groupBy, range.since(), queryUntil(range), range.granularity(),
                production, projectId, groupBy.isEmpty() ? null : limit);
    }

    /**
     * Fim da consulta. Quando o período termina "agora", vai até o fim do bucket em andamento (a
     * Vercel aceita fim no futuro e devolve só até agora). Com o instante exato, a chave do cache
     * mudava a cada pedido e nenhuma consulta era reaproveitada; assim ela vale pelo bucket todo
     * e a atualização fica por conta do TTL.
     */
    static Instant queryUntil(TimeRange range) {
        if (range.until().isBefore(range.now())) {
            return range.until();
        }
        long step = range.granularity().duration().toSeconds();
        long elapsed = Duration.between(range.since(), range.until()).toSeconds();
        long buckets = Math.max(1, (elapsed + step - 1) / step);
        return range.since().plusSeconds(buckets * step);
    }

    /** Primeiro início de bucket, contado a partir de {@code since}, que não é anterior a {@code boundary}. */
    static Instant alignUp(Instant since, Instant boundary, Duration step) {
        if (!boundary.isAfter(since)) {
            return since;
        }
        long s = step.toSeconds();
        long k = (Duration.between(since, boundary).toSeconds() + s - 1) / s;
        return since.plusSeconds(k * s);
    }

    private Future<SeriesResult> submit(MetricQuery q) {
        return executor.submit(() -> series(q));
    }

    private Cached<MetricSeries> fetch(MetricQuery q) {
        Duration ttl = q.until().isBefore(clock.instant().minus(CLOSED_AFTER)) ? CLOSED_TTL : QueryCache.DEFAULT_TTL;
        try {
            return guarded(() -> cache.get(q.cacheKey(), ttl, () -> gateway.query(q)));
        } catch (RetentionLimit e) {
            if (e.days() != null) {
                learnedRetention.put(q.scope(), e.days());
            }
            throw e;
        } catch (PlanRequired e) {
            if (q.metric().equals(schemaCapabilities(q.scope()).eventMetric())) {
                eventsBlocked.add(q.scope());
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- histórico local

    /**
     * Resultado de uma série.
     *
     * @param localUntil fim do trecho que veio do histórico local, ou {@code null} se veio tudo da Vercel
     * @param complete   se o histórico cobre todo o trecho local (sem dias desconhecidos)
     */
    private record SeriesResult(Cached<MetricSeries> cached, Instant localUntil, boolean complete) {
        MetricSeries value() {
            return cached.value();
        }

        boolean usedLocal() {
            return localUntil != null;
        }
    }

    /**
     * Série de uma consulta. Com o histórico ligado, o trecho que a Vercel não alcança mais vem do
     * histórico local e o resto vem da Vercel; o que vier da Vercel é guardado no histórico.
     */
    private SeriesResult series(MetricQuery q) {
        Instant boundary = history.enabled() ? retentionStart(q.scope()) : null;
        if (boundary == null || !q.since().isBefore(boundary)) {
            try {
                Cached<MetricSeries> remote = fetch(q);
                remember(q, remote);
                return new SeriesResult(remote, null, true);
            } catch (RetentionLimit e) {
                // limite descoberto agora: tenta de novo já juntando com o histórico
                if (boundary == null && history.enabled() && retentionStart(q.scope()) != null) {
                    return series(q);
                }
                throw e;
            }
        }
        Instant split = alignUp(q.since(), boundary, q.granularity().duration());
        if (split.isAfter(q.until())) {
            split = q.until();
        }
        Slice local = readLocal(q, q.since(), split);
        Cached<MetricSeries> remote = null;
        if (split.isBefore(q.until())) {
            MetricQuery recent = q.withSince(split);
            remote = fetch(recent);
            remember(recent, remote);
        }
        if (local.isEmpty() && remote == null) {
            throw new RetentionLimit(retentionDays(q.scope()), true);
        }
        MetricSeries merged = merge(q, local, remote == null ? null : remote.value());
        Cached<MetricSeries> cached = new Cached<>(merged, remote == null ? clock.instant() : remote.fetchedAt(),
                remote != null && remote.stale());
        return new SeriesResult(cached, split, local.covers(q.since(), split));
    }

    private static SeriesKey keyOf(MetricQuery q) {
        String project = q.projectId() != null ? q.projectId() : q.groupBy().isEmpty() ? TOTAL_SERIES : null;
        return new SeriesKey(q.metric(), q.aggregation(), q.production(), q.granularity(), project);
    }

    /**
     * Lê o histórico. A série de um projeto também pode vir da consulta de todos os projetos
     * agrupada por projeto (a da tela consolidada): usa a que cobrir mais.
     */
    private Slice readLocal(MetricQuery q, Instant from, Instant to) {
        Slice own = history.read(q.scope(), keyOf(q), from, to);
        if (q.projectId() == null || !q.groupBy().isEmpty() || own.covers(from, to)) {
            return own;
        }
        SeriesKey grouped = new SeriesKey(q.metric(), q.aggregation(), q.production(), q.granularity(), null);
        Slice all = history.read(q.scope(), grouped, from, to);
        Map<Instant, Double> mine = all.values().getOrDefault(q.projectId(), Map.of());
        Slice fromAll = new Slice(mine.isEmpty() ? Map.of() : Map.of("", mine), all.covered());
        return coveredSeconds(fromAll) > coveredSeconds(own) ? fromAll : own;
    }

    private static long coveredSeconds(Slice s) {
        return s.covered().stream().mapToLong(i -> Duration.between(i.from(), i.to()).toSeconds()).sum();
    }

    /** Guarda no histórico os buckets já assentados de uma resposta nova da Vercel. */
    private void remember(MetricQuery q, Cached<MetricSeries> result) {
        if (!history.enabled() || result.stale() || q.granularity() == null) {
            return;
        }
        if (result.fetchedAt().equals(recorded.put(q.cacheKey(), result.fetchedAt()))) {
            return;
        }
        long step = q.granularity().duration().toSeconds();
        long settled = Duration.between(q.since(), clock.instant().minus(SETTLE)).toSeconds();
        if (settled < step) {
            return;
        }
        Instant to = q.since().plusSeconds((settled / step) * step);
        if (to.isAfter(q.until())) {
            to = q.until();
        }
        String dim = q.groupBy().isEmpty() ? null : q.groupBy().get(0);
        Map<String, Map<Instant, Double>> values = new HashMap<>();
        for (MetricSeries.Point p : result.value().data()) {
            String group = dim == null ? "" : p.dimension(dim);
            values.computeIfAbsent(group, g -> new HashMap<>()).merge(p.timestamp(), p.value(), Double::sum);
        }
        history.record(q.scope(), keyOf(q), q.since(), to, values);
    }

    /** Junta o trecho local com o da Vercel numa série só. */
    private static MetricSeries merge(MetricQuery q, Slice local, MetricSeries remote) {
        String dim = q.groupBy().isEmpty() ? null : q.groupBy().get(0);
        List<MetricSeries.Point> data = new ArrayList<>();
        local.values().forEach((group, points) -> points.forEach((t, v) ->
                data.add(new MetricSeries.Point(t, dim == null ? Map.of() : Map.of(dim, group), v))));
        if (remote != null) {
            data.addAll(remote.data());
        }
        List<MetricSeries.Row> summary = new ArrayList<>();
        if (q.aggregation() == null) {
            // contagens somam entre buckets: o resumo é a soma da série
            Map<String, Double> totals = new LinkedHashMap<>();
            for (MetricSeries.Point p : data) {
                totals.merge(dim == null ? "" : p.dimension(dim), p.value(), Double::sum);
            }
            totals.forEach((group, v) ->
                    summary.add(new MetricSeries.Row(dim == null ? Map.of() : Map.of(dim, group), v)));
        }
        // únicos não somam entre buckets: sem resumo quando parte veio do histórico
        return new MetricSeries(q.metric(), q.aggregation(), q.groupBy(), q.since(), q.until(), summary, data);
    }

    /** O período anterior é opcional: se o plano não alcança, a tela segue sem comparação. */
    private static SeriesResult awaitPrevious(Future<SeriesResult> future) {
        if (future == null) {
            return null;
        }
        try {
            return await(future);
        } catch (RetentionLimit e) {
            return null;
        }
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
