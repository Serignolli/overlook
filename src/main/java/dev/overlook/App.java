package dev.overlook;

import dev.overlook.analytics.AnalyticsService;
import dev.overlook.config.AppConfig;
import dev.overlook.config.ConfigStore;
import dev.overlook.config.DataDir;
import dev.overlook.vercel.CliVercelGateway;
import dev.overlook.vercel.FixtureVercelGateway;
import dev.overlook.vercel.VercelGateway;
import dev.overlook.web.ApiRoutes;
import dev.overlook.web.CsrfGuard;
import dev.overlook.web.WebServer;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ponto de entrada. Flags: {@code --port N}, {@code --demo}, {@code --no-browser}, {@code --version}.
 */
public final class App {

    public static final String VERSION = version();

    private App() {
    }

    record Args(Integer port, boolean demo, boolean noBrowser) {
        static Args parse(String[] argv) {
            Integer port = null;
            boolean demo = false;
            boolean noBrowser = false;
            for (int i = 0; i < argv.length; i++) {
                switch (argv[i]) {
                    case "--demo" -> demo = true;
                    case "--no-browser" -> noBrowser = true;
                    case "--port" -> {
                        if (i + 1 >= argv.length) {
                            throw new IllegalArgumentException("--port precisa de um número");
                        }
                        try {
                            port = Integer.parseInt(argv[++i]);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--port precisa de um número");
                        }
                    }
                    case "--version", "-v" -> {
                        System.out.println("Overlook " + VERSION);
                        System.exit(0);
                    }
                    case "--help", "-h" -> {
                        System.out.println("Uso: overlook [--port N] [--demo] [--no-browser] [--version]");
                        System.exit(0);
                    }
                    // o macOS passa -psn_* quando o app é aberto pelo Finder em versões antigas
                    default -> {
                        if (!argv[i].startsWith("-psn_")) {
                            throw new IllegalArgumentException("Flag desconhecida: " + argv[i]);
                        }
                    }
                }
            }
            return new Args(port, demo, noBrowser);
        }
    }

    public static void main(String[] argv) {
        Args args;
        try {
            args = Args.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        Path dataDir = DataDir.ensure(DataDir.system());
        configureLogging(dataDir);

        ConfigStore store = new ConfigStore(dataDir.resolve("config.json"));
        AppConfig config = store.load();
        int port = args.port() != null ? args.port() : config.port();
        String url = "http://" + WebServer.BIND_HOST + ":" + port + "/";

        Clock clock = Clock.systemUTC();
        VercelGateway gateway = args.demo()
                ? FixtureVercelGateway.demo(clock)
                : CliVercelGateway.system(() -> store.current().vercelCliPath());
        AnalyticsService service = new AnalyticsService(gateway, clock);

        AtomicReference<WebServer> server = new AtomicReference<>();
        Runnable quit = () -> {
            System.out.println("Encerrando o Overlook.");
            System.exit(0);
        };
        ApiRoutes routes = new ApiRoutes(service, store, () -> server.get().port(), args.demo(), VERSION, quit);
        try {
            server.set(new WebServer(routes, CsrfGuard.newToken(), args.demo()).start(port));
        } catch (RuntimeException e) {
            if (isPortInUse(e)) {
                // provavelmente outra instância do Overlook: só abre o painel dela
                System.out.println("A porta " + port + " já está em uso. Abrindo " + url);
                System.out.println("Se não for o Overlook, rode com --port <outra porta>.");
                if (!args.noBrowser()) {
                    openBrowser(url);
                }
                System.exit(0);
            }
            throw e;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.get().stop();
            service.close();
        }));

        System.out.println("Overlook " + VERSION + (args.demo() ? " (demo)" : "") + " rodando em " + url);
        System.out.println("Dados e logs em " + dataDir);
        System.out.println("Ctrl+C para sair.");
        Tray.install(url, () -> openBrowser(url), quit);
        if (config.openBrowserOnStart() && !args.noBrowser()) {
            openBrowser(url);
        }
    }

    private static boolean isPortInUse(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.BindException
                    || (t.getMessage() != null && t.getMessage().toLowerCase().contains("already in use"))) {
                return true;
            }
        }
        return false;
    }

    private static String version() {
        String v = App.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static void configureLogging(Path dataDir) {
        setIfAbsent("org.slf4j.simpleLogger.logFile", dataDir.resolve("logs").resolve("overlook.log").toString());
        setIfAbsent("org.slf4j.simpleLogger.showDateTime", "true");
        setIfAbsent("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        setIfAbsent("org.slf4j.simpleLogger.log.org.eclipse.jetty", "warn");
        setIfAbsent("org.slf4j.simpleLogger.log.io.javalin", "warn");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            // sem navegador disponível: a URL já foi impressa
        }
    }
}
