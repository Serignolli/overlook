package dev.overlook.web;

import dev.overlook.analytics.AnalyticsService;
import dev.overlook.analytics.TimeRange;
import dev.overlook.config.AppConfig;
import dev.overlook.config.ConfigStore;
import dev.overlook.web.dto.SettingsView;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/** API JSON local. O frontend só fala com estas rotas. */
public final class ApiRoutes {

    private final AnalyticsService service;
    private final ConfigStore configStore;
    private final IntSupplier runningPort;
    private final boolean demo;
    private final String appVersion;
    private final Runnable shutdown;

    public ApiRoutes(AnalyticsService service, ConfigStore configStore, IntSupplier runningPort, boolean demo,
                     String appVersion, Runnable shutdown) {
        this.service = service;
        this.configStore = configStore;
        this.runningPort = runningPort;
        this.demo = demo;
        this.appVersion = appVersion;
        this.shutdown = shutdown;
    }

    public void register(Javalin app) {
        app.get("/api/health", this::health);
        app.get("/api/scopes", this::scopes);
        app.get("/api/projects", this::projects);
        app.get("/api/capabilities", this::capabilities);
        app.get("/api/overview", this::overview);
        app.get("/api/projects/{id}/breakdown", this::breakdown);
        app.get("/api/projects/{id}/timeseries", this::timeseries);
        app.get("/api/settings", this::getSettings);
        app.put("/api/settings", this::putSettings);
        app.post("/api/refresh", this::refresh);
        app.post("/api/shutdown", this::shutdown);
    }

    private void health(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", Map.of("version", appVersion, "demo", demo));
        body.put("cli", service.health());
        ctx.json(body);
    }

    private void scopes(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopes", service.scopes());
        body.put("defaultScope", service.resolveScope(null, configStore.current().defaultScope()));
        ctx.json(body);
    }

    private void projects(Context ctx) {
        String scope = scope(ctx);
        ctx.json(Map.of("scope", scope, "projects", service.projects(scope)));
    }

    private void capabilities(Context ctx) {
        ctx.json(service.capabilities(scope(ctx)));
    }

    private void overview(Context ctx) {
        ctx.json(service.overview(scope(ctx), range(ctx), production(ctx)));
    }

    private void breakdown(Context ctx) {
        String limit = ctx.queryParam("limit");
        ctx.json(service.breakdown(scope(ctx), ctx.pathParam("id"), required(ctx, "dim"), range(ctx), production(ctx),
                limit == null ? null : parseInt(limit, "limit")));
    }

    private void timeseries(Context ctx) {
        ctx.json(service.timeseries(scope(ctx), ctx.pathParam("id"), ctx.queryParam("metric"), range(ctx),
                production(ctx)));
    }

    private void getSettings(Context ctx) {
        ctx.json(view(false));
    }

    private void putSettings(Context ctx) {
        AppConfig incoming = ctx.bodyAsClass(AppConfig.class);
        String cliPath = incoming.vercelCliPath();
        if (cliPath != null && !cliPath.isBlank()) {
            Path p = Path.of(cliPath);
            if (!p.isAbsolute() || !Files.isRegularFile(p)) {
                throw new IllegalArgumentException("vercelCliPath precisa ser o caminho absoluto de um arquivo existente");
            }
        }
        AppConfig before = configStore.current();
        AppConfig saved = configStore.save(incoming);
        service.refresh();
        ctx.json(view(saved.port() != runningPort.getAsInt() || saved.port() != before.port()));
    }

    private void refresh(Context ctx) {
        service.refresh();
        ctx.status(204);
    }

    /** Encerra o app depois de responder, para a página conseguir mostrar a confirmação. */
    private void shutdown(Context ctx) {
        ctx.status(202);
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            shutdown.run();
        });
    }

    private SettingsView view(boolean requiresRestart) {
        return new SettingsView(configStore.current(), runningPort.getAsInt(), demo, configStore.file().toString(),
                requiresRestart);
    }

    // ---------------------------------------------------------------- parâmetros

    private String scope(Context ctx) {
        return service.resolveScope(ctx.queryParam("scope"), configStore.current().defaultScope());
    }

    private TimeRange range(Context ctx) {
        String range = ctx.queryParam("range");
        if (range == null && ctx.queryParam("since") == null) {
            range = configStore.current().defaultRange();
        }
        return TimeRange.parse(range, ctx.queryParam("since"), ctx.queryParam("until"), service.clock());
    }

    private boolean production(Context ctx) {
        String env = ctx.queryParam("env");
        if (env == null || env.isBlank()) {
            return configStore.current().productionOnly();
        }
        return switch (env) {
            case "production" -> true;
            case "all" -> false;
            default -> throw new IllegalArgumentException("env deve ser production ou all");
        };
    }

    private static String required(Context ctx, String name) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Parâmetro obrigatório: " + name);
        }
        return value;
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " deve ser um número");
        }
    }
}
