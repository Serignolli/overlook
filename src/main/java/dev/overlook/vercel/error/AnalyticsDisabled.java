package dev.overlook.vercel.error;

public final class AnalyticsDisabled extends VercelException {

    public static final String DOCS_URL = "https://vercel.com/docs/analytics/quickstart";

    public AnalyticsDisabled(String project) {
        super("ANALYTICS_DISABLED",
                "Nenhum dado de Web Analytics para " + project + " no período.",
                "Confira se o Web Analytics está ativado no projeto: " + DOCS_URL);
    }
}
