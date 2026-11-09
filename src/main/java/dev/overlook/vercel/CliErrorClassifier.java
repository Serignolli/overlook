package dev.overlook.vercel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.overlook.vercel.error.CliFailure;
import dev.overlook.vercel.error.CliTimeout;
import dev.overlook.vercel.error.NotLoggedIn;
import dev.overlook.vercel.error.PlanRequired;
import dev.overlook.vercel.error.RateLimited;
import dev.overlook.vercel.error.VercelException;
import java.util.Locale;

/**
 * Converte uma execução com falha em erro tipado.
 *
 * <p>Com {@code --format json}, a CLI escreve erros no stdout como
 * {@code {"error": {"code": "...", "message": "..."}}} e sai com código 1. Os códigos abaixo
 * foram tirados do código-fonte da CLI 63.1.0 ({@code handleApiError} e validações do
 * comando {@code metrics}). Sem login, a CLI escreve no stderr
 * {@code "No existing credentials found. Please run `vercel login` or pass `--token`"}.
 */
public final class CliErrorClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SecretMasker masker;

    public CliErrorClassifier(SecretMasker masker) {
        this.masker = masker;
    }

    public VercelException classify(CliResult result) {
        JsonNode error = jsonError(result.stdout());
        if (error != null) {
            return fromJson(error.path("code").asText(""), error.path("message").asText(""));
        }
        String stderr = result.stderr() == null ? "" : result.stderr().toLowerCase(Locale.ROOT);
        if (stderr.contains("no existing credentials found")
                || stderr.contains("token is not valid")
                || stderr.contains("not_authorized")
                || stderr.contains("vercel login")) {
            return new NotLoggedIn();
        }
        if (stderr.contains("rate limit") || stderr.contains("too many requests")) {
            return new RateLimited(30);
        }
        return new CliFailure("CLI_ERROR",
                "A Vercel CLI falhou (código de saída " + result.exitCode() + "). Detalhes no log local.");
    }

    VercelException fromJson(String code, String message) {
        String safeMessage = masker.mask(message);
        return switch (code) {
            case "RATE_LIMITED" -> new RateLimited(30);
            case "PAYMENT_REQUIRED" -> new PlanRequired(safeMessage);
            case "SCHEMA_UNAUTHORIZED", "NOT_AUTHORIZED", "MISSING_USER" -> new NotLoggedIn();
            case "TIMEOUT" -> new CliTimeout();
            default -> new CliFailure(code.isBlank() ? "CLI_ERROR" : code, safeMessage);
        };
    }

    private static JsonNode jsonError(String stdout) {
        if (stdout == null) {
            return null;
        }
        String trimmed = stdout.strip();
        int idx = trimmed.indexOf('{');
        if (idx < 0) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed.substring(idx));
            JsonNode error = root.get("error");
            return error != null && error.isObject() ? error : null;
        } catch (Exception e) {
            return null;
        }
    }
}
