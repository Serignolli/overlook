package dev.overlook.vercel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.overlook.Fixtures;
import dev.overlook.vercel.error.UnexpectedOutput;
import dev.overlook.vercel.model.MetricSeries;
import dev.overlook.vercel.parse.MetricsJsonParser;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MetricsJsonParserTest {

    private final MetricsJsonParser parser = new MetricsJsonParser();

    @Test
    void parsesSeriesWithoutGroupBy() {
        MetricSeries s = parser.parse(Fixtures.read("pageviews-total.json"));

        assertThat(s.metric()).isEqualTo("vercel.analytics.page_view.count");
        assertThat(s.aggregation()).isEqualTo("sum");
        assertThat(s.groupBy()).isEmpty();
        assertThat(s.start()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(s.summary()).singleElement().extracting(MetricSeries.Row::value).isEqualTo(3600d);
        assertThat(s.data()).extracting(MetricSeries.Point::value).containsExactly(1000d, 1200d, 1400d);
        assertThat(s.data().get(1).timestamp()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
    }

    @Test
    void parsesGroupedSeries() {
        MetricSeries s = parser.parse(Fixtures.read("pageviews-by-project.json"));

        assertThat(s.groupBy()).containsExactly("projectId");
        assertThat(s.summary()).extracting(r -> r.dimension("projectId")).containsExactly("prj_aaa", "prj_bbb");
        assertThat(s.data()).hasSize(4);
        assertThat(s.data().get(1).dimension("projectId")).isEqualTo("prj_bbb");
        assertThat(s.data().get(1).value()).isEqualTo(20d);
    }

    @Test
    void emptySeriesIsNotAnError() {
        MetricSeries s = parser.parse(Fixtures.read("pageviews-empty.json"));

        assertThat(s.isEmpty()).isTrue();
        assertThat(s.groupBy()).containsExactly("requestPath");
    }

    @Test
    void ignoresExtraFieldsLeadingNoiseAndNullValues() {
        MetricSeries s = parser.parse(Fixtures.read("pageviews-extra-fields.json"));

        assertThat(s.aggregation()).isEqualTo("unique/visitorId");
        assertThat(s.start()).isEqualTo(Instant.ofEpochMilli(1790812800000L));
        assertThat(s.end()).isEqualTo(Instant.parse("2026-10-03T00:00:00Z"));
        assertThat(s.summary()).singleElement().extracting(MetricSeries.Row::value).isEqualTo(42d);
        assertThat(s.data()).extracting(MetricSeries.Point::value).containsExactly(30d, 0d);
    }

    @Test
    void missingRequiredFieldIsTypedError() {
        assertThatThrownBy(() -> parser.parse(Fixtures.read("pageviews-missing-summary.json")))
                .isInstanceOf(UnexpectedOutput.class)
                .hasMessageContaining("summary");
    }

    @Test
    void garbageIsTypedError() {
        assertThatThrownBy(() -> parser.parse("Querying metrics... done")).isInstanceOf(UnexpectedOutput.class);
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(UnexpectedOutput.class);
        assertThatThrownBy(() -> parser.parse("[1,2,3]")).isInstanceOf(UnexpectedOutput.class);
    }

    @Test
    void fallsBackToSoleNumericColumnWhenNameDiffers() {
        String json = """
                {"query":{"metric":"vercel.analytics.page_view.count","aggregation":"sum","groupBy":["country"]},
                 "summary":[{"country":"BR","renamed_value":7}],
                 "data":[]}
                """;
        MetricSeries s = parser.parse(json);

        assertThat(s.summary()).singleElement().satisfies(r -> {
            assertThat(r.dimension("country")).isEqualTo("BR");
            assertThat(r.value()).isEqualTo(7d);
        });
    }

    @Test
    void rollupColumnMatchesCli() {
        assertThat(MetricsJsonParser.rollupColumnName("vercel.analytics.page_view.count", "unique/visitorId"))
                .isEqualTo("vercel_analytics_page_view_count_unique_visitorId");
    }
}
