package dev.overlook.vercel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.overlook.vercel.error.CliOutdated;
import dev.overlook.vercel.error.RateLimited;
import dev.overlook.vercel.model.CliHealth;
import dev.overlook.vercel.model.Granularity;
import dev.overlook.vercel.model.MetricQuery;
import dev.overlook.vercel.model.MetricSeries;
import dev.overlook.vercel.model.Project;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliVercelGatewayTest {

    private static CliVercelGateway gateway(String... fakeFlags) {
        List<String> command = FakeCli.command(fakeFlags);
        return new CliVercelGateway(() -> null, path -> command,
                cmd -> new CliRunner(cmd, new HashMap<>(System.getenv()), Duration.ofSeconds(30), new SecretMasker(null)),
                new SecretMasker(null));
    }

    private static MetricQuery query(String scope) {
        return new MetricQuery(scope, "vercel.analytics.page_view.count", null, List.of("projectId"),
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-03T00:00:00Z"), Granularity.DAY,
                true, null, 20);
    }

    @Test
    void healthReportsVersionLoginAndCurrentScope() {
        CliHealth h = gateway().health();

        assertThat(h.ok()).isTrue();
        assertThat(h.version()).isEqualTo("63.1.0");
        assertThat(h.loggedIn()).isTrue();
        assertThat(h.currentScope().slug()).isEqualTo("acme");
    }

    @Test
    void healthFlagsOutdatedCli() {
        CliHealth h = gateway("--fake-version=50.0.0").health();

        assertThat(h.outdated()).isTrue();
        assertThat(h.errorCode()).isEqualTo("CLI_OUTDATED");
    }

    @Test
    void outdatedCliNeverRunsMetrics() {
        assertThatThrownBy(() -> gateway("--fake-version=50.0.0").query(query("acme"))).isInstanceOf(CliOutdated.class);
    }

    @Test
    void followsProjectPagination() {
        List<Project> projects = gateway().listProjects("acme");

        assertThat(projects).extracting(Project::id).containsExactly("prj_aaa", "prj_bbb", "prj_ccc", "prj_ddd");
    }

    @Test
    void queriesMetricsAndParses() {
        MetricSeries s = gateway().query(query("acme"));

        assertThat(s.summary()).hasSize(2);
        assertThat(gateway().schema("acme").metrics()).hasSize(2);
    }

    @Test
    void mapsJsonErrors() {
        assertThatThrownBy(() -> gateway().query(query("acme-limited"))).isInstanceOf(RateLimited.class);
    }

    @Test
    void queryArgsUseAllOrProjectNeverBoth() {
        assertThat(query("acme").toArgs()).contains("--all", "--prod", "--group-by", "projectId", "--granularity", "1d")
                .doesNotContain("--project");
        MetricQuery single = new MetricQuery("acme", "m", "unique/visitorId", List.of(), Instant.EPOCH, Instant.EPOCH,
                null, false, "prj_aaa", null);
        assertThat(single.toArgs()).containsSequence("--project", "prj_aaa").containsSequence("--aggregation", "unique/visitorId")
                .doesNotContain("--all", "--prod", "--limit");
    }

    @Test
    void versionComparison() {
        assertThat(CliVercelGateway.isOlder("54.17.9", "54.18.0")).isTrue();
        assertThat(CliVercelGateway.isOlder("54.18.0", "54.18.0")).isFalse();
        assertThat(CliVercelGateway.isOlder("63.1.0", "54.18.0")).isFalse();
        assertThat(CliVercelGateway.isOlder("9.99.99", "54.18.0")).isTrue();
    }
}
