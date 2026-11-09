package dev.overlook.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.overlook.MutableClock;
import dev.overlook.analytics.Results.Breakdown;
import dev.overlook.analytics.Results.Overview;
import dev.overlook.analytics.Results.ProjectRow;
import dev.overlook.analytics.Results.Timeseries;
import dev.overlook.vercel.VercelGateway;
import dev.overlook.vercel.error.RateLimited;
import dev.overlook.vercel.model.CliHealth;
import dev.overlook.vercel.model.MetricQuery;
import dev.overlook.vercel.model.MetricSchema;
import dev.overlook.vercel.model.MetricSeries;
import dev.overlook.vercel.model.Project;
import dev.overlook.vercel.model.SchemaInfo;
import dev.overlook.vercel.model.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    static final Instant SINCE = Instant.parse("2026-10-02T00:00:00Z");
    static final String PV = "vercel.analytics.page_view.count";
    static final String EV = "vercel.analytics.event.count";

    /** Gateway controlado: prj_a faz 100/dia (50 no dia aberto), prj_b 10/dia; no período anterior prj_a faz 50/dia. */
    static final class StubGateway implements VercelGateway {
        final List<MetricQuery> queries = new CopyOnWriteArrayList<>();
        List<Project> projects = List.of(
                new Project("prj_a", "alpha", "https://a.example.com"),
                new Project("prj_b", "beta", null),
                new Project("prj_c", "gamma", null));
        boolean withEvents = false;
        boolean rateLimitEvents = false;

        @Override
        public CliHealth health() {
            return new CliHealth(true, "63.1.0", "54.18.0", false, true, null, false, null, null, null);
        }

        @Override
        public List<Scope> listScopes() {
            return List.of(new Scope("team_1", "acme", "Acme", "pro", true), new Scope("user_1", "me", "Me", "hobby", false));
        }

        @Override
        public List<Project> listProjects(String scope) {
            return projects;
        }

        @Override
        public SchemaInfo schema(String scope) {
            List<MetricSchema> metrics = new ArrayList<>();
            metrics.add(new MetricSchema(PV, "", "count", List.of("projectId", "requestPath", "country", "visitorId"),
                    List.of("sum", "unique"), "sum"));
            if (withEvents) {
                metrics.add(new MetricSchema(EV, "", "count", List.of("projectId", "eventName"), List.of("sum"), null));
            }
            return new SchemaInfo(metrics);
        }

        @Override
        public MetricSeries query(MetricQuery q) {
            queries.add(q);
            boolean unique = q.aggregation() != null && q.aggregation().startsWith("unique");
            boolean previous = q.until().compareTo(SINCE) <= 0;
            if (q.metric().equals(EV) && rateLimitEvents) {
                throw new RateLimited(30);
            }
            if (!q.groupBy().isEmpty() && !q.groupBy().get(0).equals("projectId")) {
                String dim = q.groupBy().get(0);
                return new MetricSeries(q.metric(), "sum", q.groupBy(), q.since(), q.until(), List.of(
                        new MetricSeries.Row(Map.of(dim, "/b"), 5),
                        new MetricSeries.Row(Map.of(dim, "/a"), 50),
                        new MetricSeries.Row(Map.of(dim, "/zero"), 0)), List.of());
            }
            if (unique) {
                List<MetricSeries.Row> summary = q.allProjects()
                        ? List.of(row("prj_a", previous ? 20 : 40), row("prj_b", 5))
                        : List.of(new MetricSeries.Row(Map.of(), 30));
                List<MetricSeries.Point> data = new ArrayList<>();
                for (Instant t = q.since(); t.isBefore(q.until()); t = t.plus(Duration.ofDays(1))) {
                    data.add(new MetricSeries.Point(t, Map.of(), 10));
                }
                return new MetricSeries(q.metric(), q.aggregation(), q.groupBy(), q.since(), q.until(), summary,
                        q.allProjects() ? List.of() : data);
            }
            List<MetricSeries.Point> data = new ArrayList<>();
            for (Instant t = q.since(); t.isBefore(q.until()); t = t.plus(Duration.ofDays(1))) {
                boolean open = t.plus(Duration.ofDays(1)).isAfter(NOW);
                if (q.metric().equals(EV)) {
                    data.add(new MetricSeries.Point(t, Map.of(), 3));
                    continue;
                }
                double a = previous ? 50 : open ? 50 : 100;
                if (q.groupBy().isEmpty()) {
                    data.add(new MetricSeries.Point(t, Map.of(), a));
                } else {
                    data.add(new MetricSeries.Point(t, Map.of("projectId", "prj_a"), a));
                    data.add(new MetricSeries.Point(t, Map.of("projectId", "prj_b"), 10));
                    for (Project extra : projects.subList(3, projects.size())) {
                        data.add(new MetricSeries.Point(t, Map.of("projectId", extra.id()), 1));
                    }
                }
            }
            return new MetricSeries(q.metric(), "sum", q.groupBy(), q.since(), q.until(), List.of(), data);
        }

        private static MetricSeries.Row row(String project, double v) {
            return new MetricSeries.Row(Map.of("projectId", project), v);
        }
    }

    private final MutableClock clock = new MutableClock(NOW);
    private final StubGateway gateway = new StubGateway();
    private final AnalyticsService service = new AnalyticsService(gateway, clock);

    private TimeRange sevenDays() {
        return TimeRange.parse("7d", null, null, clock);
    }

    @Test
    void overviewTotalsAndComparesOnlyClosedBuckets() {
        Overview o = service.overview("acme", sevenDays(), true);

        // atual: alpha 6*100 + 50 (aberto) + beta 7*10
        assertThat(o.cards().pageViews().value()).isEqualTo(720d);
        // anterior: alpha 7*50 + beta 7*10
        assertThat(o.cards().pageViews().previous()).isEqualTo(420d);
        // fechados: (600+60) contra os 6 primeiros do anterior (300+60)
        assertThat(o.cards().pageViews().change()).isCloseTo(300d / 360d, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(o.chart().openBucketIndex()).isEqualTo(6);
        assertThat(o.chart().buckets()).hasSize(7);
        assertThat(o.range().closedBuckets()).isEqualTo(6);
    }

    @Test
    void visitorsAreLabeledProjectSumAndUseClosedRange() {
        Overview o = service.overview("acme", sevenDays(), true);

        assertThat(o.cards().visitors().label()).isEqualTo(AggregationRules.PROJECT_SUM_LABEL);
        assertThat(o.cards().visitors().value()).isEqualTo(45d);
        assertThat(o.cards().visitors().previous()).isEqualTo(25d);
        assertThat(o.cards().visitors().note()).contains("mais de uma vez");
        assertThat(gateway.queries).filteredOn(q -> "unique/visitorId".equals(q.aggregation()))
                .allSatisfy(q -> assertThat(q.until()).isBeforeOrEqualTo(Instant.parse("2026-10-08T00:00:00Z")));
    }

    @Test
    void rankingIncludesProjectsWithoutData() {
        Overview o = service.overview("acme", sevenDays(), true);

        assertThat(o.projects()).extracting(ProjectRow::id).containsExactly("prj_a", "prj_b", "prj_c");
        assertThat(o.projects().get(2).hasData()).isFalse();
        assertThat(o.projects().get(0).visitors()).isEqualTo(40d);
        assertThat(o.projects().get(0).change()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void usesOneAllProjectsQueryGroupedByProject() {
        service.overview("acme", sevenDays(), true);

        assertThat(gateway.queries).filteredOn(q -> q.metric().equals(PV) && q.aggregation() == null)
                .hasSize(2)
                .allSatisfy(q -> {
                    assertThat(q.allProjects()).isTrue();
                    assertThat(q.groupBy()).containsExactly("projectId");
                    assertThat(q.production()).isTrue();
                });
    }

    @Test
    void chartKeepsTopEightPlusOthers() {
        List<Project> many = new ArrayList<>(gateway.projects);
        for (int i = 0; i < 9; i++) {
            many.add(new Project("prj_x" + i, "extra" + i, null));
        }
        gateway.projects = many;

        Overview o = service.overview("acme", sevenDays(), true);

        assertThat(o.chart().lines()).hasSize(AnalyticsService.CHART_TOP_PROJECTS + 1);
        assertThat(o.chart().lines().getLast().id()).isEqualTo(AnalyticsService.OTHERS_ID);
    }

    @Test
    void failingBlockDoesNotBreakTheScreen() {
        gateway.withEvents = true;
        gateway.rateLimitEvents = true;

        Overview o = service.overview("acme", sevenDays(), true);

        assertThat(o.cards().events().error()).isNotNull();
        assertThat(o.cards().events().error().code()).isEqualTo("RATE_LIMITED");
        assertThat(o.cards().events().error().retryAfterSeconds()).isEqualTo(30);
        assertThat(o.cards().pageViews().value()).isEqualTo(720d);
    }

    @Test
    void eventsCardWhenAvailable() {
        gateway.withEvents = true;

        Overview o = service.overview("acme", sevenDays(), true);

        assertThat(o.cards().events().value()).isEqualTo(21d);
        assertThat(o.cards().events().change()).isEqualTo(0d);
    }

    @Test
    void uniqueTimeseriesTotalComesFromVercelNotFromSummingDays() {
        Timeseries ts = service.timeseries("acme", "prj_a", "visitors", sevenDays(), true);

        assertThat(ts.aggClass()).isEqualTo("UNIQUE");
        assertThat(ts.buckets()).hasSize(7).allSatisfy(b -> assertThat(b.value()).isEqualTo(10d));
        assertThat(ts.total()).isEqualTo(30d);
        assertThat(ts.buckets().getLast().inProgress()).isTrue();
        assertThat(ts.buckets()).allSatisfy(b -> assertThat(b.source()).isEqualTo("vercel"));
    }

    @Test
    void additiveTimeseriesTotalIsSumOfBuckets() {
        Timeseries ts = service.timeseries("acme", "prj_a", "pageViews", sevenDays(), true);

        assertThat(ts.total()).isEqualTo(650d);
        assertThat(ts.aggClass()).isEqualTo("ADDITIVE");
    }

    @Test
    void breakdownUsesSchemaDimensionAndSorts() {
        Breakdown b = service.breakdown("acme", "prj_a", "pages", sevenDays(), true, 10);

        assertThat(b.vercelDimension()).isEqualTo("requestPath");
        assertThat(b.rows()).extracting(Results.BreakdownRow::value).containsExactly("/a", "/b");
        assertThat(gateway.queries.getLast().projectId()).isEqualTo("prj_a");
        assertThat(gateway.queries.getLast().limit()).isEqualTo(10);
    }

    @Test
    void dimensionsMissingFromSchemaAreNotOffered() {
        Capabilities caps = service.capabilities("acme");

        assertThat(caps.dimensions()).containsOnlyKeys("pages", "countries");
        assertThat(caps.visitors()).isTrue();
        assertThatThrownBy(() -> service.breakdown("acme", "prj_a", "devices", sevenDays(), true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeIds() {
        assertThatThrownBy(() -> service.timeseries("acme", "prj a;rm", "pageViews", sevenDays(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesPersonalScope() {
        assertThat(service.resolveScope("personal", null)).isEqualTo("me");
        assertThat(service.resolveScope(null, "acme")).isEqualTo("acme");
        assertThatThrownBy(() -> service.resolveScope("nope", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
