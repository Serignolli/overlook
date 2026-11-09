package dev.overlook.vercel;

import dev.overlook.vercel.error.CliNotFound;
import dev.overlook.vercel.error.CliTimeout;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executa a Vercel CLI. Regras de privacidade aplicadas aqui:
 * <ul>
 *   <li>lista de argumentos via {@link ProcessBuilder}, nunca string de shell;</li>
 *   <li>{@code --token} é proibido nos argumentos: o token só passa por {@code VERCEL_TOKEN};</li>
 *   <li>o ambiente do subprocesso é montado a partir de uma lista fechada de variáveis;</li>
 *   <li>timeout de 30 s e no máximo 2 processos simultâneos.</li>
 * </ul>
 */
public final class CliRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final int MAX_CONCURRENT = 2;

    /**
     * Variáveis repassadas ao subprocesso. Além de {@code VERCEL_TOKEN} e {@code PATH}, a CLI
     * precisa dos diretórios do usuário para achar o próprio login, e o Node no Windows precisa
     * de {@code SystemRoot}. Proxies são repassados para a CLI alcançar a Vercel em redes corporativas.
     */
    static final Set<String> ENV_ALLOWLIST = Set.of(
            "VERCEL_TOKEN", "PATH",
            "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
            "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR", "PATHEXT", "COMSPEC",
            "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "NODE_EXTRA_CA_CERTS");

    /** Valores que nossos argumentos podem ter: IDs, slugs, nomes de métrica, datas ISO, números. */
    private static final Pattern SAFE_ARG = Pattern.compile("[A-Za-z0-9_@./:+,=-]+");

    private final List<String> command;
    private final Map<String, String> parentEnv;
    private final Duration timeout;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT, true);
    private final SecretMasker masker;

    public CliRunner(List<String> command, Map<String, String> parentEnv, Duration timeout, SecretMasker masker) {
        this.command = List.copyOf(command);
        this.parentEnv = Map.copyOf(parentEnv);
        this.timeout = timeout;
        this.masker = masker;
    }

    public CliRunner(List<String> command) {
        this(command, System.getenv(), DEFAULT_TIMEOUT, new SecretMasker());
    }

    public List<String> command() {
        return command;
    }

    public CliResult run(List<String> args) {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(args));
        pb.environment().clear();
        pb.environment().putAll(childEnvironment(parentEnv, command.get(0)));
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));

        boolean acquired = false;
        try {
            slots.acquire();
            acquired = true;
            String printable = masker.mask(String.join(" ", args));
            log.debug("vercel {}", printable);
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new CliNotFound(command.get(0));
            }
            CompletableFuture<String> out = drain(process.getInputStream());
            CompletableFuture<String> err = drain(process.getErrorStream());
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new CliTimeout();
            }
            CliResult result = new CliResult(process.exitValue(), out.join(), err.join());
            if (!result.ok()) {
                log.warn("vercel {} saiu com código {}: {}", printable, result.exitCode(),
                        masker.mask(abbreviate(result.stderr() + " " + result.stdout())));
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliTimeout();
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }

    List<String> buildCommand(List<String> args) {
        args.forEach(CliRunner::validateArg);
        List<String> full = new ArrayList<>(command);
        full.addAll(args);
        return full;
    }

    static void validateArg(String arg) {
        if (arg == null || arg.isEmpty()) {
            throw new IllegalArgumentException("argumento vazio");
        }
        String lower = arg.toLowerCase(Locale.ROOT);
        if (lower.equals("-t") || lower.startsWith("--token")) {
            throw new IllegalArgumentException("--token nunca é passado na linha de comando");
        }
        if (!SAFE_ARG.matcher(arg).matches()) {
            throw new IllegalArgumentException("argumento com caracteres não permitidos");
        }
    }

    /**
     * Como {@link #childEnvironment(Map)}, com a pasta do executável (ex.: a do {@code node}) na
     * frente do {@code PATH}, para a CLI achar o próprio runtime quando o app foi aberto sem terminal.
     */
    static Map<String, String> childEnvironment(Map<String, String> parent, String executable) {
        Map<String, String> env = childEnvironment(parent);
        Path exe = Path.of(executable);
        if (exe.isAbsolute() && exe.getParent() != null) {
            String key = env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            String current = env.get(key);
            env.put(key, current == null || current.isBlank()
                    ? exe.getParent().toString()
                    : exe.getParent() + File.pathSeparator + current);
        }
        return env;
    }

    static Map<String, String> childEnvironment(Map<String, String> parent) {
        Map<String, String> env = new HashMap<>();
        parent.forEach((k, v) -> {
            if (v != null && ENV_ALLOWLIST.contains(k.toUpperCase(Locale.ROOT))) {
                env.put(k, v);
            }
        });
        env.put("NO_COLOR", "1");
        env.put("FORCE_COLOR", "0");
        env.put("VERCEL_TELEMETRY_DISABLED", "1");
        return env;
    }

    private static File nullDevice() {
        return new File(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null");
    }

    private static CompletableFuture<String> drain(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                in.transferTo(buf);
                return buf.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Thread::startVirtualThread);
    }

    private static String abbreviate(String s) {
        String trimmed = s.strip();
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000) + "…";
    }
}
