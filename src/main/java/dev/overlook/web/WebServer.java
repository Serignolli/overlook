package dev.overlook.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.overlook.vercel.SecretMasker;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Servidor HTTP embutido. O bind é fixo em {@code 127.0.0.1}, sem opção de mudar.
 */
public final class WebServer {

    public static final String BIND_HOST = "127.0.0.1";

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'";

    private final Javalin app;
    private final CsrfGuard csrf;
    private final boolean demo;
    private volatile int boundPort;

    public WebServer(ApiRoutes routes, String csrfToken, boolean demo) {
        this.demo = demo;
        this.csrf = new CsrfGuard(csrfToken, this::port);
        HostGuard hostGuard = new HostGuard(this::port);
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(mapper, false));
            cfg.staticFiles.add(sf -> {
                sf.hostedPath = "/";
                sf.directory = "/web";
                sf.location = Location.CLASSPATH;
                // index.html é servido pela rota "/" com o token CSRF injetado
                sf.skipFileFunction = req -> req.getRequestURI().equals("/") || req.getRequestURI().endsWith(".html");
            });
        });

        app.before(ctx -> {
            hostGuard.check(ctx);
            csrf.check(ctx);
            ctx.header("Content-Security-Policy", CSP);
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("Referrer-Policy", "no-referrer");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Cross-Origin-Resource-Policy", "same-origin");
            if (ctx.path().startsWith("/api/")) {
                ctx.header("Cache-Control", "no-store");
            }
        });
        app.get("/", ctx -> ctx.contentType("text/html; charset=utf-8").result(indexHtml()));
        routes.register(app);
        ErrorMapper.register(app, new SecretMasker());
    }

    public WebServer start(int port) {
        app.start(BIND_HOST, port);
        boundPort = app.port();
        return this;
    }

    public void stop() {
        app.stop();
    }

    public int port() {
        return boundPort;
    }

    public String url() {
        return "http://" + BIND_HOST + ":" + boundPort + "/";
    }

    String indexHtml() {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                throw new IllegalStateException("index.html ausente");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__OVERLOOK_CSRF__", csrf.token())
                    .replace("__OVERLOOK_DEMO__", String.valueOf(demo));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
