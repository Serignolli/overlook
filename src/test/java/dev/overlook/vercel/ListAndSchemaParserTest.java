package dev.overlook.vercel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.overlook.Fixtures;
import dev.overlook.vercel.error.UnexpectedOutput;
import dev.overlook.vercel.model.MetricSchema;
import dev.overlook.vercel.model.SchemaInfo;
import dev.overlook.vercel.model.Scope;
import dev.overlook.vercel.parse.ProjectListParser;
import dev.overlook.vercel.parse.SchemaParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListAndSchemaParserTest {

    @Test
    void schemaAcceptsStringAndObjectDimensions() {
        SchemaInfo schema = new SchemaParser().parse(Fixtures.read("schema.json"));

        assertThat(schema.metrics()).extracting(MetricSchema::id)
                .containsExactly("vercel.analytics.event.count", "vercel.analytics.page_view.count");
        MetricSchema events = schema.find("vercel.analytics.event.count").orElseThrow();
        assertThat(events.dimensions()).containsExactly("projectId", "eventName", "country");
        MetricSchema pv = schema.find("vercel.analytics.page_view.count").orElseThrow();
        assertThat(pv.supportsAggregation("unique")).isTrue();
        assertThat(pv.hasDimension("visitorId")).isTrue();
        assertThat(pv.defaultAggregation()).isEqualTo("sum");
    }

    @Test
    void schemaWithoutIdIsTypedError() {
        assertThatThrownBy(() -> new SchemaParser().parse("[{\"description\":\"x\"}]"))
                .isInstanceOf(UnexpectedOutput.class);
    }

    @Test
    void parsesProjectsWithPagination() {
        ProjectListParser.Page page = new ProjectListParser().parseProjects(Fixtures.read("projects.json"));

        assertThat(page.projects()).hasSize(2);
        assertThat(page.projects().get(0).productionUrl()).isEqualTo("https://site.example.com");
        assertThat(page.projects().get(1).productionUrl()).isNull();
        assertThat(page.next()).isEqualTo("1789990000000");
    }

    @Test
    void parsesScopes() {
        List<Scope> scopes = new ProjectListParser().parseScopes(Fixtures.read("teams.json"));

        assertThat(scopes).extracting(Scope::slug).containsExactly("acme", "jane");
        assertThat(scopes.get(0).current()).isTrue();
        assertThat(scopes.get(0).personal()).isFalse();
        assertThat(scopes.get(1).personal()).isTrue();
    }
}
