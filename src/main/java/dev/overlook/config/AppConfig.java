package dev.overlook.config;

import java.util.Set;

/**
 * Conteúdo de {@code config.json}. Nunca guarda token.
 *
 * @param defaultScope slug do time, ou {@code personal}
 */
public record AppConfig(
        int port,
        String defaultScope,
        String defaultRange,
        boolean productionOnly,
        String vercelCliPath,
        History history,
        AutoRefresh autoRefresh,
        boolean openBrowserOnStart) {

    public static final Set<String> RANGES = Set.of("24h", "7d", "30d", "90d");
    public static final Set<Integer> RETENTION_MONTHS = Set.of(0, 3, 6, 12, 24);
    public static final int MIN_REFRESH_MINUTES = 15;

    public record History(boolean enabled, int retentionMonths) {
    }

    public record AutoRefresh(boolean enabled, int intervalMinutes) {
    }

    public static AppConfig defaults() {
        return new AppConfig(7878, "personal", "7d", true, null,
                new History(false, 0), new AutoRefresh(false, 30), true);
    }

    /** Preenche campos ausentes de um arquivo antigo ou editado à mão. */
    public AppConfig withDefaults() {
        AppConfig d = defaults();
        return new AppConfig(
                port == 0 ? d.port : port,
                defaultScope == null || defaultScope.isBlank() ? d.defaultScope : defaultScope,
                defaultRange == null ? d.defaultRange : defaultRange,
                productionOnly,
                vercelCliPath == null || vercelCliPath.isBlank() ? null : vercelCliPath,
                history == null ? d.history : history,
                autoRefresh == null ? d.autoRefresh : autoRefresh,
                openBrowserOnStart);
    }

    public AppConfig validate() {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("port deve estar entre 1024 e 65535");
        }
        if (!RANGES.contains(defaultRange)) {
            throw new IllegalArgumentException("defaultRange deve ser um de " + RANGES);
        }
        if (!RETENTION_MONTHS.contains(history.retentionMonths())) {
            throw new IllegalArgumentException("history.retentionMonths deve ser um de " + RETENTION_MONTHS);
        }
        if (autoRefresh.intervalMinutes() < MIN_REFRESH_MINUTES) {
            throw new IllegalArgumentException("autoRefresh.intervalMinutes mínimo é " + MIN_REFRESH_MINUTES);
        }
        if (!defaultScope.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("defaultScope inválido");
        }
        return this;
    }

    public AppConfig withPort(int newPort) {
        return new AppConfig(newPort, defaultScope, defaultRange, productionOnly, vercelCliPath, history, autoRefresh,
                openBrowserOnStart);
    }

    public AppConfig withOpenBrowserOnStart(boolean open) {
        return new AppConfig(port, defaultScope, defaultRange, productionOnly, vercelCliPath, history, autoRefresh, open);
    }
}
