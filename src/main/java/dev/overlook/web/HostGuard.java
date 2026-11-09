package dev.overlook.web;

import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Proteção contra DNS rebinding: só aceita requisições cujo {@code Host} seja
 * {@code 127.0.0.1} ou {@code localhost} na porta do próprio servidor.
 */
public final class HostGuard {

    static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "localhost");

    private final IntSupplier port;

    public HostGuard(IntSupplier port) {
        this.port = port;
    }

    public void check(Context ctx) {
        if (!allowed(ctx.header("Host"), port.getAsInt())) {
            throw new ForbiddenResponse("Host não permitido");
        }
    }

    static boolean allowed(String hostHeader, int port) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String value = hostHeader.strip().toLowerCase(Locale.ROOT);
        String host = value;
        String portPart = null;
        int colon = value.lastIndexOf(':');
        if (colon >= 0) {
            host = value.substring(0, colon);
            portPart = value.substring(colon + 1);
        }
        if (!ALLOWED_HOSTS.contains(host)) {
            return false;
        }
        return portPart == null ? port == 80 : portPart.equals(String.valueOf(port));
    }

    /** {@code Origin} de requisições de escrita, quando presente, também precisa ser local. */
    static boolean allowedOrigin(String origin, int port) {
        if (origin == null) {
            return true;
        }
        String o = origin.toLowerCase(Locale.ROOT);
        return o.equals("http://127.0.0.1:" + port) || o.equals("http://localhost:" + port);
    }
}
