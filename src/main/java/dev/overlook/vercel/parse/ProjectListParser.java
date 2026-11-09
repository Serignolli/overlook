package dev.overlook.vercel.parse;

import com.fasterxml.jackson.databind.JsonNode;
import dev.overlook.vercel.error.UnexpectedOutput;
import dev.overlook.vercel.model.Project;
import dev.overlook.vercel.model.Scope;
import java.util.ArrayList;
import java.util.List;

/**
 * Converte {@code vercel project ls --format json} ({@code {projects: [...], pagination: {next}}})
 * e {@code vercel teams ls --format json} ({@code {teams: [{id, slug, name, plan, current}]}}).
 */
public final class ProjectListParser {

    public record Page(List<Project> projects, String next) {
        public Page {
            projects = List.copyOf(projects);
        }
    }

    public Page parseProjects(String text) {
        String what = "vercel project ls";
        JsonNode root = Json.read(text, what);
        JsonNode list = Json.required(root, "projects", what);
        if (!list.isArray()) {
            throw new UnexpectedOutput(what + ": 'projects' não é uma lista", null);
        }
        List<Project> projects = new ArrayList<>();
        for (JsonNode p : list) {
            projects.add(new Project(
                    Json.required(p, "id", what).asText(),
                    Json.required(p, "name", what).asText(),
                    Json.text(p, "latestProductionUrl")));
        }
        JsonNode pagination = root.get("pagination");
        String next = pagination == null ? null : Json.text(pagination, "next");
        return new Page(projects, next);
    }

    public List<Scope> parseScopes(String text) {
        String what = "vercel teams ls";
        JsonNode root = Json.read(text, what);
        JsonNode list = Json.required(root, "teams", what);
        if (!list.isArray()) {
            throw new UnexpectedOutput(what + ": 'teams' não é uma lista", null);
        }
        List<Scope> scopes = new ArrayList<>();
        for (JsonNode t : list) {
            scopes.add(new Scope(
                    Json.required(t, "id", what).asText(),
                    Json.required(t, "slug", what).asText(),
                    Json.text(t, "name"),
                    Json.text(t, "plan"),
                    t.path("current").asBoolean(false)));
        }
        return scopes;
    }
}
