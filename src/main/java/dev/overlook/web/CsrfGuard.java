package dev.overlook.web;

import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.HandlerType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.function.IntSupplier;

/** Rotas que mudam estado exigem {@code X-Overlook-Csrf} com o token gerado na inicialização. */
public final class CsrfGuard {

    public static final String HEADER = "X-Overlook-Csrf";
    private static final Set<HandlerType> WRITES = Set.of(HandlerType.POST, HandlerType.PUT, HandlerType.PATCH,
            HandlerType.DELETE);

    private final String token;
    private final IntSupplier port;

    public CsrfGuard(String token, IntSupplier port) {
        this.token = token;
        this.port = port;
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String token() {
        return token;
    }

    public void check(Context ctx) {
        if (!WRITES.contains(ctx.method())) {
            return;
        }
        String sent = ctx.header(HEADER);
        boolean valid = sent != null && MessageDigest.isEqual(
                sent.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!valid || !HostGuard.allowedOrigin(ctx.header("Origin"), port.getAsInt())) {
            throw new ForbiddenResponse("Token CSRF ausente ou inválido");
        }
    }
}
