package dev.overlook.vercel;

import dev.overlook.vercel.error.CliOutdated;
import dev.overlook.vercel.error.UnexpectedOutput;
import dev.overlook.vercel.error.VercelException;
import dev.overlook.vercel.model.CliHealth;
import dev.overlook.vercel.model.MetricQuery;
import dev.overlook.vercel.model.MetricSeries;
import dev.overlook.vercel.model.Project;
import dev.overlook.vercel.model.SchemaInfo;
import dev.overlook.vercel.model.Scope;
import dev.overlook.vercel.parse.MetricsJsonParser;
import dev.overlook.vercel.parse.ProjectListParser;
import dev.overlook.vercel.parse.SchemaParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementação de produção: tudo passa pela Vercel CLI do usuário. */
public final class CliVercelGateway implements VercelGateway {

    private static final Logger log = LoggerFactory.getLogger(CliVercelGateway.class);

    /**
     * Primeira versão publicada depois do changelog de 26/06/2026 (Web Analytics no
     * {@code vercel metrics}). Não verificado contra a CLI real; ver README.
     */
    public static final String MINIMUM_VERSION = "54.18.0";

    static final String ANALYTICS_PREFIX = "vercel.analytics";
    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final int PROJECT_PAGE_LIMIT = 100;
    private static final int MAX_PROJECT_PAGES = 20;

    private final Supplier<String> configuredPath;
    private final Function<String, List<String>> locate;
    private final Function<List<String>, CliRunner> runnerFactory;
    private final SecretMasker masker;
    private final CliErrorClassifier classifier;
    private final MetricsJsonParser metricsParser = new MetricsJsonParser();
    private final SchemaParser schemaParser = new SchemaParser();
    private final ProjectListParser listParser = new ProjectListParser();

    private volatile CliRunner runner;
    private volatile String runnerPath;
    private volatile String checkedVersion;

    public CliVercelGateway(Supplier<String> configuredPath, Function<String, List<String>> locate,
                            Function<List<String>, CliRunner> runnerFactory, SecretMasker masker) {
        this.configuredPath = configuredPath;
        this.locate = locate;
        this.runnerFactory = runnerFactory;
        this.masker = masker;
        this.classifier = new CliErrorClassifier(masker);
    }

    public static CliVercelGateway system(Supplier<String> configuredPath) {
        CliLocator locator = CliLocator.system();
        return new CliVercelGateway(configuredPath, locator::locate, CliRunner::new, new SecretMasker());
    }

    @Override
    public CliHealth health() {
        String version = null;
        try {
            version = version();
            if (isOlder(version, MINIMUM_VERSION)) {
                CliOutdated e = new CliOutdated(version, MINIMUM_VERSION);
                return new CliHealth(true, version, MINIMUM_VERSION, true, false, null, false,
                        e.code(), e.getMessage(), e.action());
            }
            Scope current = listScopes().stream().filter(Scope::current).findFirst().orElse(null);
            return new CliHealth(true, version, MINIMUM_VERSION, false, true, current, false, null, null, null);
        } catch (VercelException e) {
            boolean installed = version != null;
            return new CliHealth(installed, version, MINIMUM_VERSION, false, false, null, false,
                    e.code(), e.getMessage(), e.action());
        }
    }

    @Override
    public List<Scope> listScopes() {
        return listParser.parseScopes(run(List.of("teams", "ls", "--format", "json", "--limit", "100"), null));
    }

    @Override
    public List<Project> listProjects(String scope) {
        List<Project> all = new ArrayList<>();
        String next = null;
        for (int page = 0; page < MAX_PROJECT_PAGES; page++) {
            List<String> args = new ArrayList<>(List.of("project", "ls", "--format", "json",
                    "--limit", String.valueOf(PROJECT_PAGE_LIMIT)));
            if (next != null) {
                args.add("--next");
                args.add(next);
            }
            ProjectListParser.Page result = listParser.parseProjects(run(args, scope));
            all.addAll(result.projects());
            if (result.next() == null || result.next().isBlank() || result.next().equals(next)) {
                break;
            }
            next = result.next();
        }
        return all;
    }

    @Override
    public SchemaInfo schema(String scope) {
        ensureMetricsSupported();
        return schemaParser.parse(run(List.of("metrics", "schema", ANALYTICS_PREFIX, "--format", "json"), scope));
    }

    @Override
    public MetricSeries query(MetricQuery query) {
        ensureMetricsSupported();
        List<String> args = new ArrayList<>();
        args.add("metrics");
        args.addAll(query.toArgs());
        args.add("--format");
        args.add("json");
        String stdout = run(args, query.scope());
        try {
            return metricsParser.parse(stdout);
        } catch (UnexpectedOutput e) {
            log.warn("Saída inesperada de vercel metrics (CLI {}): {}", checkedVersion, masker.mask(stdout));
            throw e;
        }
    }

    String version() {
        CliResult result = runner().run(List.of("--version"));
        String text = result.stdout() + "\n" + result.stderr();
        Matcher m = SEMVER.matcher(text);
        if (!result.ok() || !m.find()) {
            throw classifier.classify(result);
        }
        return m.group();
    }

    private void ensureMetricsSupported() {
        if (checkedVersion != null) {
            return;
        }
        String version = version();
        if (isOlder(version, MINIMUM_VERSION)) {
            throw new CliOutdated(version, MINIMUM_VERSION);
        }
        checkedVersion = version;
    }

    private String run(List<String> args, String scope) {
        List<String> full = new ArrayList<>(args);
        if (scope != null && !scope.isBlank()) {
            full.add("--scope");
            full.add(scope);
        }
        full.add("--no-color");
        CliResult result = runner().run(full);
        if (!result.ok()) {
            throw classifier.classify(result);
        }
        return result.stdout();
    }

    private synchronized CliRunner runner() {
        String path = configuredPath.get();
        if (runner == null || !Objects.equals(path, runnerPath)) {
            runner = runnerFactory.apply(locate.apply(path));
            runnerPath = path;
            checkedVersion = null;
        }
        return runner;
    }

    static boolean isOlder(String version, String minimum) {
        int[] a = parse(version);
        int[] b = parse(minimum);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] < b[i];
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        Matcher m = SEMVER.matcher(version);
        if (!m.find()) {
            return new int[] {0, 0, 0};
        }
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }
}
