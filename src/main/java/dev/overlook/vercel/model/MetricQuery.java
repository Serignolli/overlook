package dev.overlook.vercel.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Uma consulta a {@code vercel metrics}. {@code projectId == null} significa {@code --all}.
 */
public record MetricQuery(
        String scope,
        String metric,
        String aggregation,
        List<String> groupBy,
        Instant since,
        Instant until,
        Granularity granularity,
        boolean production,
        String projectId,
        Integer limit) {

    public MetricQuery {
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
    }

    public boolean allProjects() {
        return projectId == null;
    }

    /** Argumentos da CLI, sem o comando {@code metrics} e sem flags globais. */
    public List<String> toArgs() {
        List<String> args = new ArrayList<>();
        args.add(metric);
        if (aggregation != null) {
            args.add("--aggregation");
            args.add(aggregation);
        }
        for (String dim : groupBy) {
            args.add("--group-by");
            args.add(dim);
        }
        args.add("--since");
        args.add(since.toString());
        args.add("--until");
        args.add(until.toString());
        if (granularity != null) {
            args.add("--granularity");
            args.add(granularity.flag());
        }
        if (limit != null) {
            args.add("--limit");
            args.add(String.valueOf(limit));
        }
        if (production) {
            args.add("--prod");
        }
        if (projectId == null) {
            args.add("--all");
        } else {
            args.add("--project");
            args.add(projectId);
        }
        return args;
    }

    /** Chave estável para cache. */
    public String cacheKey() {
        return String.join("|", scope, String.join(" ", toArgs()));
    }
}
