package dev.overlook.vercel;

import dev.overlook.vercel.model.CliHealth;
import dev.overlook.vercel.model.MetricQuery;
import dev.overlook.vercel.model.MetricSeries;
import dev.overlook.vercel.model.Project;
import dev.overlook.vercel.model.SchemaInfo;
import dev.overlook.vercel.model.Scope;
import java.util.List;

/**
 * Único ponto do app que conversa com a Vercel. Erros são lançados como subclasses de
 * {@link dev.overlook.vercel.error.VercelException}.
 */
public interface VercelGateway {

    CliHealth health();

    List<Scope> listScopes();

    List<Project> listProjects(String scope);

    /** Métricas de Web Analytics disponíveis no escopo (prefixo {@code vercel.analytics}). */
    SchemaInfo schema(String scope);

    MetricSeries query(MetricQuery query);
}
