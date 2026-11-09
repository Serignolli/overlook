package dev.overlook.vercel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.overlook.vercel.error.CliFailure;
import dev.overlook.vercel.model.CliHealth;
import dev.overlook.vercel.model.Granularity;
import dev.overlook.vercel.model.MetricQuery;
import dev.overlook.vercel.model.MetricSeries;
import dev.overlook.vercel.model.Project;
import dev.overlook.vercel.model.SchemaInfo;
import dev.overlook.vercel.model.Scope;
import dev.overlook.vercel.parse.MetricsJsonParser;
import dev.overlook.vercel.parse.ProjectListParser;
import dev.overlook.vercel.parse.SchemaParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway sem CLI nem conta, para {@code --demo} e testes.
 *
 * <p>Escopos, projetos e schema vêm de arquivos JSON no formato real da CLI. As séries são
 * geradas de forma determinística a partir da consulta e serializadas no mesmo formato de
 * {@code vercel metrics --format json}, para passar pelo parser de produção.
 */
public final class FixtureVercelGateway implements VercelGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, List<Map.Entry<String, Double>>> DIMENSION_VALUES = Map.of(
            "requestPath", weights("/", 30, "/pricing", 14, "/docs", 12, "/blog", 9, "/blog/launch-week", 7,
                    "/about", 5, "/careers", 4, "/contact", 3, "/docs/getting-started", 6, "/changelog", 3,
                    "/integrations", 2.5, "/legal/privacy", 1),
            "referrerHostname", weights("", 34, "google.com", 28, "github.com", 11, "x.com", 7, "news.ycombinator.com", 6,
                    "linkedin.com", 5, "duckduckgo.com", 3, "bing.com", 2, "reddit.com", 2.5, "dev.to", 1.5),
            "country", weights("BR", 31, "US", 24, "DE", 7, "PT", 6, "GB", 5, "IN", 5, "FR", 4, "AR", 3.5,
                    "CA", 3, "MX", 3, "ES", 2.5, "JP", 2),
            "deviceType", weights("desktop", 58, "mobile", 39, "tablet", 3),
            "osName", weights("Windows", 34, "macOS", 26, "iOS", 18, "Android", 19, "Linux", 3),
            "clientName", weights("Chrome", 61, "Safari", 21, "Firefox", 7, "Edge", 8, "Opera", 3),
            "eventName", weights("signup", 22, "cta_click", 31, "newsletter_subscribe", 12, "download", 9,
                    "checkout_started", 7, "contact_submit", 4));

    private final String base;
    private final Clock clock;
    private final MetricsJsonParser metricsParser = new MetricsJsonParser();
    private final ProjectListParser listParser = new ProjectListParser();
    private final SchemaParser schemaParser = new SchemaParser();

    /** @param base caminho no classpath com {@code teams.json}, {@code projects-<slug>.json} e {@code schema.json} */
    public FixtureVercelGateway(String base, Clock clock) {
        this.base = base.endsWith("/") ? base : base + "/";
        this.clock = clock;
    }

    public static FixtureVercelGateway demo(Clock clock) {
        return new FixtureVercelGateway("/demo/", clock);
    }

    @Override
    public CliHealth health() {
        Scope current = listScopes().stream().filter(Scope::current).findFirst().orElse(null);
        return new CliHealth(true, "demo", CliVercelGateway.MINIMUM_VERSION, false, true, current, true, null, null, null);
    }

    @Override
    public List<Scope> listScopes() {
        return listParser.parseScopes(resource("teams.json"));
    }

    @Override
    public List<Project> listProjects(String scope) {
        return listParser.parseProjects(resource("projects-" + resolveScope(scope) + ".json")).projects();
    }

    @Override
    public SchemaInfo schema(String scope) {
        return schemaParser.parse(resource("schema.json"));
    }

    @Override
    public MetricSeries query(MetricQuery query) {
        return metricsParser.parse(render(query));
    }

    /** Gera o JSON exatamente como {@code vercel metrics --format json} imprimiria. */
    String render(MetricQuery q) {
        List<Project> projects = listProjects(q.scope());
        if (!q.allProjects()) {
            projects = projects.stream().filter(p -> p.id().equals(q.projectId()) || p.name().equals(q.projectId())).toList();
            if (projects.isEmpty()) {
                throw new CliFailure("PROJECT_NOT_FOUND", "Project \"" + q.projectId() + "\" was not found.");
            }
        }
        Granularity g = q.granularity() == null ? Granularity.DAY : q.granularity();
        Instant start = truncate(q.since(), g);
        Instant end = q.until();
        String aggregation = q.aggregation() != null ? q.aggregation() : "sum";
        boolean unique = aggregation.startsWith("unique");
        String column = MetricsJsonParser.rollupColumnName(q.metric(), aggregation);

        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode query = root.putObject("query");
        query.put("metric", q.metric());
        query.put("aggregation", aggregation);
        ArrayNode groupBy = query.putArray("groupBy");
        q.groupBy().forEach(groupBy::add);
        if (q.production()) {
            query.put("filter", "environment:production");
        }
        query.put("startTime", start.toString());
        query.put("endTime", end.toString());
        query.putObject("granularity").put(g == Granularity.DAY ? "days" : "hours", 1);

        // valor por bucket e grupo
        Map<Map<String, String>, Map<Instant, Double>> grid = new LinkedHashMap<>();
        Instant now = clock.instant();
        for (Instant t = start; t.isBefore(end); t = t.plus(g.duration())) {
            for (Project p : projects) {
                if (isSilent(p)) {
                    continue;
                }
                double total = bucketValue(p, q.metric(), t, g, now, q.production());
                for (Map.Entry<Map<String, String>, Double> share : split(p, q.groupBy()).entrySet()) {
                    double v = Math.round(total * share.getValue() * (unique ? 0.42 : 1));
                    if (v > 0) {
                        grid.computeIfAbsent(share.getKey(), k -> new LinkedHashMap<>()).merge(t, v, Double::sum);
                    }
                }
            }
        }

        List<Map.Entry<Map<String, String>, Double>> totals = new ArrayList<>();
        grid.forEach((dims, buckets) -> {
            double sum = buckets.values().stream().mapToDouble(Double::doubleValue).sum();
            // únicos do período inteiro: menos que a soma dos únicos diários
            totals.add(Map.entry(dims, unique ? Math.round(sum * 0.63) : sum));
        });
        totals.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int limit = q.limit() == null ? 10 : q.limit();
        List<Map.Entry<Map<String, String>, Double>> kept = q.groupBy().isEmpty() ? totals : totals.stream().limit(limit).toList();

        ArrayNode summary = root.putArray("summary");
        for (Map.Entry<Map<String, String>, Double> e : kept) {
            ObjectNode row = summary.addObject();
            e.getKey().forEach(row::put);
            row.put(column, e.getValue());
        }
        if (q.groupBy().isEmpty() && kept.isEmpty()) {
            summary.addObject().put(column, 0);
        }
        ArrayNode data = root.putArray("data");
        for (Map.Entry<Map<String, String>, Double> e : kept) {
            grid.get(e.getKey()).forEach((t, v) -> {
                ObjectNode row = data.addObject();
                row.put("timestamp", t.toString());
                e.getKey().forEach(row::put);
                row.put(column, v);
            });
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Projeto sem Web Analytics ativado: nunca tem dados. */
    private static boolean isSilent(Project p) {
        return p.name().equals("internal-tools");
    }

    private static double bucketValue(Project p, String metric, Instant t, Granularity g, Instant now, boolean prod) {
        int seed = Math.abs(p.id().hashCode());
        double daily = 40 + (seed % 9000) * (seed % 3 == 0 ? 1.4 : 0.6);
        if (metric.contains("event")) {
            daily *= 0.07;
        }
        DayOfWeek dow = t.atZone(ZoneOffset.UTC).getDayOfWeek();
        double weekly = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY ? 0.62 : 1.0;
        double noise = 0.8 + 0.4 * pseudoRandom(seed, t.getEpochSecond() / 3600);
        double value = daily * weekly * noise * (prod ? 1 : 1.08);
        if (g == Granularity.HOUR) {
            int hour = t.atZone(ZoneOffset.UTC).getHour();
            double curve = 0.3 + Math.max(0, Math.sin((hour - 6) / 24.0 * 2 * Math.PI)) * 1.6;
            value = value / 24 * curve;
        }
        Instant bucketEnd = t.plus(g.duration());
        if (bucketEnd.isAfter(now)) {
            double elapsed = Math.max(0, Duration.between(t, now).toMillis()) / (double) g.duration().toMillis();
            value *= Math.min(1, elapsed);
        }
        return value;
    }

    private static Map<Map<String, String>, Double> split(Project p, List<String> groupBy) {
        Map<Map<String, String>, Double> shares = new LinkedHashMap<>();
        shares.put(Map.of(), 1.0);
        for (String dim : groupBy) {
            Map<Map<String, String>, Double> next = new LinkedHashMap<>();
            for (Map.Entry<Map<String, String>, Double> partial : shares.entrySet()) {
                for (Map.Entry<String, Double> value : values(p, dim)) {
                    Map<String, String> dims = new LinkedHashMap<>(partial.getKey());
                    dims.put(dim, value.getKey());
                    next.put(dims, partial.getValue() * value.getValue());
                }
            }
            shares = next;
        }
        return shares;
    }

    private static List<Map.Entry<String, Double>> values(Project p, String dim) {
        if (dim.equals("projectId")) {
            return List.of(Map.entry(p.id(), 1.0));
        }
        if (dim.equals("environment")) {
            return List.of(Map.entry("production", 1.0));
        }
        return DIMENSION_VALUES.getOrDefault(dim, List.of(Map.entry("(unknown)", 1.0)));
    }

    private static List<Map.Entry<String, Double>> weights(Object... pairs) {
        List<Map.Entry<String, Double>> list = new ArrayList<>();
        double total = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            total += ((Number) pairs[i + 1]).doubleValue();
        }
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(Map.entry((String) pairs[i], ((Number) pairs[i + 1]).doubleValue() / total));
        }
        return List.copyOf(list);
    }

    private static double pseudoRandom(int seed, long n) {
        long x = seed * 0x9E3779B97F4A7C15L + n * 0xBF58476D1CE4E5B9L;
        x ^= x >>> 31;
        x *= 0x94D049BB133111EBL;
        x ^= x >>> 29;
        return (x >>> 11) / (double) (1L << 53);
    }

    private static Instant truncate(Instant t, Granularity g) {
        return t.truncatedTo(g == Granularity.DAY ? ChronoUnit.DAYS : ChronoUnit.HOURS);
    }

    private String resolveScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return listScopes().stream().filter(Scope::current).findFirst().map(Scope::slug).orElseThrow();
        }
        return listScopes().stream()
                .filter(s -> s.slug().equals(scope) || s.id().equals(scope))
                .findFirst()
                .map(Scope::slug)
                .orElseThrow(() -> new CliFailure("SCOPE_NOT_FOUND", "Escopo desconhecido: " + scope));
    }

    private String resource(String name) {
        try (InputStream in = FixtureVercelGateway.class.getResourceAsStream(base + name)) {
            if (in == null) {
                throw new CliFailure("FIXTURE_NOT_FOUND", "Fixture ausente: " + base + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
