package dev.overlook.web;

import dev.overlook.vercel.SecretMasker;
import dev.overlook.vercel.error.CliNotFound;
import dev.overlook.vercel.error.CliOutdated;
import dev.overlook.vercel.error.CliTimeout;
import dev.overlook.vercel.error.NotLoggedIn;
import dev.overlook.vercel.error.PlanRequired;
import dev.overlook.vercel.error.RateLimited;
import dev.overlook.vercel.error.UnexpectedOutput;
import dev.overlook.vercel.error.VercelException;
import dev.overlook.web.dto.ApiError;
import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converte exceções em JSON para a UI. Nunca devolve stack trace nem stderr cru. */
public final class ErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(ErrorMapper.class);

    private ErrorMapper() {
    }

    public static void register(Javalin app, SecretMasker masker) {
        app.exception(VercelException.class, (e, ctx) -> {
            Integer retry = e instanceof RateLimited r ? r.retryAfterSeconds() : null;
            if (retry != null) {
                ctx.header("Retry-After", String.valueOf(retry));
            }
            ctx.status(statusOf(e)).json(ApiError.of(e.code(), e.getMessage(), e.action(), retry));
        });
        app.exception(IllegalArgumentException.class, (e, ctx) ->
                ctx.status(400).json(ApiError.of("BAD_REQUEST", e.getMessage(), null, null)));
        app.exception(HttpResponseException.class, (e, ctx) ->
                ctx.status(e.getStatus()).json(ApiError.of("HTTP_" + e.getStatus(), e.getMessage(), null, null)));
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Erro inesperado em {} {}: {}", ctx.method(), ctx.path(), masker.mask(String.valueOf(e)));
            ctx.status(500).json(ApiError.of("INTERNAL", "Erro inesperado. Detalhes no log local.", null, null));
        });
    }

    static int statusOf(VercelException e) {
        if (e instanceof CliNotFound || e instanceof CliOutdated) {
            return 503;
        }
        if (e instanceof NotLoggedIn) {
            return 401;
        }
        if (e instanceof RateLimited) {
            return 429;
        }
        if (e instanceof CliTimeout) {
            return 504;
        }
        if (e instanceof PlanRequired) {
            return 402;
        }
        if (e instanceof UnexpectedOutput) {
            return 502;
        }
        return switch (e.code()) {
            case "PROJECT_NOT_FOUND", "METRIC_NOT_FOUND", "UNKNOWN_METRIC" -> 404;
            case "FORBIDDEN" -> 403;
            default -> 502;
        };
    }
}
