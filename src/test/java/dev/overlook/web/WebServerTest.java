package dev.overlook.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.overlook.MutableClock;
import dev.overlook.analytics.AnalyticsService;
import dev.overlook.config.ConfigStore;
import dev.overlook.vercel.FixtureVercelGateway;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSRF = "test-csrf-token";
    private static final java.util.concurrent.atomic.AtomicInteger SHUTDOWNS = new java.util.concurrent.atomic.AtomicInteger();

    @TempDir
    static Path dataDir;

    private static WebServer server;
    private static AnalyticsService service;
    private static int port;

    @BeforeAll
    static void start() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T12:00:00Z"));
        service = new AnalyticsService(FixtureVercelGateway.demo(clock), clock);
        ConfigStore store = new ConfigStore(dataDir.resolve("config.json"));
        store.load();
        WebServer[] ref = new WebServer[1];
        ApiRoutes routes = new ApiRoutes(service, store, () -> ref[0].port(), true, "test", () -> SHUTDOWNS.incrementAndGet());
        ref[0] = new WebServer(routes, CSRF, true).start(0);
        server = ref[0];
        port = server.port();
    }

    @AfterAll
    static void stop() {
        server.stop();
        service.close();
    }

    private static JsonNode json(RawHttp.Response r) throws IOException {
        return JSON.readTree(r.body());
    }

    @Test
    void bindsOnlyToLoopback() throws IOException {
        assertThat(WebServer.BIND_HOST).isEqualTo("127.0.0.1");
        Optional<InetAddress> external = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                .findFirst();
        if (external.isPresent()) {
            try (Socket s = new Socket()) {
                boolean connected;
                try {
                    s.connect(new InetSocketAddress(external.get(), port), 1000);
                    connected = true;
                } catch (IOException expected) {
                    connected = false;
                }
                assertThat(connected).as("servidor não pode aceitar conexões em " + external.get()).isFalse();
            }
        }
        assertThat(RawHttp.get(port, "/api/health").status()).isEqualTo(200);
    }

    @Test
    void hostGuardRejectsForeignHosts() {
        assertThat(RawHttp.send(port, "GET", "/api/health", Map.of("Host", "evil.example.com:" + port), null).status())
                .isEqualTo(403);
        assertThat(RawHttp.send(port, "GET", "/", Map.of("Host", "evil.example.com"), null).status()).isEqualTo(403);
        assertThat(RawHttp.send(port, "GET", "/app.js", Map.of("Host", "127.0.0.1.evil.com:" + port), null).status())
                .isEqualTo(403);
        assertThat(RawHttp.send(port, "GET", "/api/health", Map.of("Host", "localhost:" + port), null).status())
                .isEqualTo(200);
    }

    @Test
    void writeRoutesRequireCsrf() {
        assertThat(RawHttp.send(port, "POST", "/api/refresh", Map.of(), "").status()).isEqualTo(403);
        assertThat(RawHttp.send(port, "POST", "/api/refresh", Map.of("X-Overlook-Csrf", "wrong"), "").status())
                .isEqualTo(403);
        assertThat(RawHttp.send(port, "POST", "/api/refresh", Map.of("X-Overlook-Csrf", CSRF), "").status())
                .isEqualTo(204);
        assertThat(RawHttp.send(port, "POST", "/api/refresh",
                Map.of("X-Overlook-Csrf", CSRF, "Origin", "https://evil.example.com"), "").status()).isEqualTo(403);
    }

    @Test
    void shutdownRequiresCsrf() throws InterruptedException {
        assertThat(RawHttp.send(port, "POST", "/api/shutdown", Map.of(), "").status()).isEqualTo(403);
        assertThat(RawHttp.send(port, "POST", "/api/shutdown", Map.of("X-Overlook-Csrf", CSRF), "").status())
                .isEqualTo(202);
        Thread.sleep(600);
        assertThat(SHUTDOWNS.get()).isEqualTo(1);
    }

    @Test
    void settingsRoundTripNeverStoresToken() throws IOException {
        JsonNode current = json(RawHttp.get(port, "/api/settings")).get("config");
        String body = current.toString().replace("\"defaultRange\":\"7d\"", "\"defaultRange\":\"30d\"");

        assertThat(RawHttp.send(port, "PUT", "/api/settings", Map.of(), body).status()).isEqualTo(403);
        RawHttp.Response saved = RawHttp.send(port, "PUT", "/api/settings", Map.of("X-Overlook-Csrf", CSRF), body);

        assertThat(saved.status()).isEqualTo(200);
        assertThat(json(saved).at("/config/defaultRange").asText()).isEqualTo("30d");
        String file = Files.readString(dataDir.resolve("config.json"));
        assertThat(file).contains("\"defaultRange\" : \"30d\"").doesNotContainIgnoringCase("token");

        String invalid = current.toString().replace("\"intervalMinutes\":30", "\"intervalMinutes\":5");
        assertThat(RawHttp.send(port, "PUT", "/api/settings", Map.of("X-Overlook-Csrf", CSRF), invalid).status())
                .isEqualTo(400);
    }

    @Test
    void indexHasCsrfAndRestrictiveCsp() {
        RawHttp.Response r = RawHttp.get(port, "/");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("content=\"" + CSRF + "\"").doesNotContain("__OVERLOOK_CSRF__");
        assertThat(r.header("Content-Security-Policy")).startsWith("default-src 'self'");
        assertThat(RawHttp.get(port, "/index.html").status()).isEqualTo(404);
        assertThat(RawHttp.get(port, "/app.js").status()).isEqualTo(200);
        assertThat(RawHttp.get(port, "/vendor/uplot/uPlot.esm.js").status()).isEqualTo(200);
    }

    @Test
    void healthScopesAndProjects() throws IOException {
        JsonNode health = json(RawHttp.get(port, "/api/health"));
        assertThat(health.at("/cli/loggedIn").asBoolean()).isTrue();
        assertThat(health.at("/app/demo").asBoolean()).isTrue();

        JsonNode scopes = json(RawHttp.get(port, "/api/scopes"));
        assertThat(scopes.get("scopes")).hasSize(2);
        assertThat(scopes.get("defaultScope").asText()).isEqualTo("demo-user");

        JsonNode projects = json(RawHttp.get(port, "/api/projects?scope=acme"));
        assertThat(projects.get("projects")).hasSize(11);
    }

    @Test
    void overviewWithFixtures() throws IOException {
        RawHttp.Response r = RawHttp.get(port, "/api/overview?scope=acme&range=7d&env=production");
        JsonNode o = json(r);

        assertThat(r.status()).isEqualTo(200);
        assertThat(o.at("/cards/pageViews/value").asDouble()).isPositive();
        assertThat(o.at("/cards/visitors/label").asText()).isEqualTo("Soma dos visitantes por projeto");
        assertThat(o.at("/chart/openBucketIndex").asInt()).isEqualTo(6);
        assertThat(o.at("/chart/lines")).hasSize(9);
        assertThat(o.at("/range/since").asText()).isEqualTo("2026-10-02T00:00:00Z");
        JsonNode silent = null;
        for (JsonNode p : o.get("projects")) {
            if (p.get("name").asText().equals("internal-tools")) {
                silent = p;
            }
        }
        assertThat(silent).isNotNull();
        assertThat(silent.get("hasData").asBoolean()).isFalse();
    }

    @Test
    void projectRoutes() throws IOException {
        String id = "prj_demoDocs00000000000000002";
        JsonNode b = json(RawHttp.get(port, "/api/projects/" + id + "/breakdown?scope=acme&dim=countries&range=30d"));
        assertThat(b.get("vercelDimension").asText()).isEqualTo("country");
        assertThat(b.get("rows").get(0).get("value").asText()).isEqualTo("BR");

        JsonNode ts = json(RawHttp.get(port, "/api/projects/" + id + "/timeseries?scope=acme&metric=pageViews&range=24h"));
        assertThat(ts.get("buckets")).hasSize(24);
        assertThat(ts.get("buckets").get(23).get("inProgress").asBoolean()).isTrue();
        assertThat(ts.get("buckets").get(0).get("source").asText()).isEqualTo("vercel");
    }

    @Test
    void errorsAreJsonWithoutStackTraces() throws IOException {
        RawHttp.Response bad = RawHttp.get(port, "/api/overview?scope=acme&range=1y");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(json(bad).at("/error/code").asText()).isEqualTo("BAD_REQUEST");

        RawHttp.Response unknown = RawHttp.get(port, "/api/projects/prj_nope/timeseries?scope=acme");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body()).doesNotContain("Exception").doesNotContain("at dev.overlook");
    }
}
