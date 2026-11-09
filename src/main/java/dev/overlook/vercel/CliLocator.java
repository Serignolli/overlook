package dev.overlook.vercel;

import dev.overlook.vercel.error.CliNotFound;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Descobre como invocar a Vercel CLI.
 *
 * <ul>
 *   <li>No Windows o npm instala {@code vercel.cmd}, um script batch. Para não passar argumentos
 *       pelo {@code cmd.exe}, o locator procura o {@code vc.js} do pacote ao lado do script e chama
 *       o {@code node} diretamente.</li>
 *   <li>No macOS e no Linux, um app aberto pelo Finder ou pelo menu recebe um {@code PATH} mínimo,
 *       sem Homebrew, nvm, Volta etc. Por isso o locator também procura nesses lugares e, se a CLI
 *       for um script Node, chama o {@code node} que está ao lado dela.</li>
 * </ul>
 */
public final class CliLocator {

    private final Map<String, String> env;
    private final boolean windows;

    public CliLocator(Map<String, String> env, boolean windows) {
        this.env = env;
        this.windows = windows;
    }

    public static CliLocator system() {
        return new CliLocator(System.getenv(), System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
    }

    /** Prefixo de comando para executar a CLI, ex.: {@code [node, .../vc.js]} ou {@code [/usr/local/bin/vercel]}. */
    public List<String> locate(String configuredPath) {
        Path executable = configuredPath == null || configuredPath.isBlank()
                ? find("vercel").orElseThrow(() -> new CliNotFound("não está no PATH"))
                : Path.of(configuredPath);
        if (!Files.isRegularFile(executable)) {
            throw new CliNotFound(executable.toString());
        }
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (windows && (name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".ps1"))) {
            Optional<List<String>> viaNode = windowsNodeEntrypoint(executable.getParent());
            if (viaNode.isPresent()) {
                return viaNode.get();
            }
        }
        if (!windows) {
            Optional<List<String>> viaNode = unixNodeEntrypoint(executable);
            if (viaNode.isPresent()) {
                return viaNode.get();
            }
        }
        return List.of(executable.toString());
    }

    private Optional<List<String>> windowsNodeEntrypoint(Path binDir) {
        Path script = binDir.resolve("node_modules").resolve("vercel").resolve("dist").resolve("vc.js");
        if (!Files.isRegularFile(script)) {
            return Optional.empty();
        }
        Path siblingNode = binDir.resolve("node.exe");
        Optional<Path> node = Files.isRegularFile(siblingNode) ? Optional.of(siblingNode) : find("node");
        return node.map(n -> List.of(n.toString(), script.toString()));
    }

    /** Se a CLI é um script com shebang do Node, roda {@code node script} para não depender do PATH. */
    private Optional<List<String>> unixNodeEntrypoint(Path executable) {
        try {
            Path real = executable.toRealPath();
            if (!isNodeScript(real)) {
                return Optional.empty();
            }
            Path sibling = executable.getParent().resolve("node");
            Optional<Path> node = Files.isRegularFile(sibling) ? Optional.of(sibling) : find("node");
            return node.map(n -> List.of(n.toString(), real.toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isNodeScript(Path file) {
        if (file.getFileName().toString().endsWith(".js") || file.getFileName().toString().endsWith(".mjs")) {
            return true;
        }
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            String first = r.readLine();
            return first != null && first.startsWith("#!") && first.contains("node");
        } catch (IOException e) {
            return false;
        }
    }

    Optional<Path> find(String name) {
        List<String> exts = new ArrayList<>();
        if (windows) {
            String pathext = lookup("PATHEXT");
            for (String ext : (pathext == null ? ".EXE;.CMD;.BAT" : pathext).split(";")) {
                if (!ext.isBlank()) {
                    exts.add(ext.toLowerCase(Locale.ROOT));
                }
            }
        } else {
            exts.add("");
        }
        for (Path dir : searchDirs()) {
            for (String ext : exts) {
                Path candidate = dir.resolve(name + ext);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** {@code PATH} primeiro, depois os lugares onde gerenciadores de Node costumam instalar. */
    List<Path> searchDirs() {
        Set<String> dirs = new LinkedHashSet<>();
        String path = lookup("PATH");
        if (path != null) {
            for (String dir : path.split(windows ? ";" : File.pathSeparator)) {
                if (!dir.isBlank()) {
                    dirs.add(dir.strip());
                }
            }
        }
        String home = lookup(windows ? "USERPROFILE" : "HOME");
        if (windows) {
            addIfSet(dirs, lookup("APPDATA"), "npm");
            addIfSet(dirs, lookup("LOCALAPPDATA"), "pnpm");
            addIfSet(dirs, home, ".volta\\bin");
            addIfSet(dirs, lookup("ProgramFiles"), "nodejs");
        } else {
            dirs.add("/opt/homebrew/bin");
            dirs.add("/usr/local/bin");
            dirs.add("/usr/bin");
            addIfSet(dirs, home, ".npm-global/bin");
            addIfSet(dirs, home, ".volta/bin");
            addIfSet(dirs, home, ".bun/bin");
            addIfSet(dirs, home, "Library/pnpm");
            addIfSet(dirs, home, ".local/share/pnpm");
            addIfSet(dirs, home, ".local/bin");
            addIfSet(dirs, home, ".yarn/bin");
            addIfSet(dirs, home, ".asdf/shims");
            if (home != null) {
                newestNvmBin(Path.of(home, ".nvm", "versions", "node")).ifPresent(p -> dirs.add(p.toString()));
            }
        }
        List<Path> out = new ArrayList<>();
        for (String d : dirs) {
            try {
                out.add(Path.of(d));
            } catch (InvalidPathException e) {
                // entrada inválida no PATH: ignora
            }
        }
        return out;
    }

    private static void addIfSet(Set<String> dirs, String base, String child) {
        if (base != null && !base.isBlank()) {
            dirs.add(Path.of(base, child).toString());
        }
    }

    private static Optional<Path> newestNvmBin(Path versions) {
        if (!Files.isDirectory(versions)) {
            return Optional.empty();
        }
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versions)) {
            stream.forEach(found::add);
        } catch (IOException e) {
            return Optional.empty();
        }
        return found.stream()
                .max(Comparator.comparing(p -> versionKey(p.getFileName().toString())))
                .map(p -> p.resolve("bin"));
    }

    private static String versionKey(String v) {
        StringBuilder key = new StringBuilder();
        for (String part : v.replaceFirst("^v", "").split("\\.")) {
            key.append(String.format("%06d", part.matches("\\d+") ? Integer.parseInt(part) : 0));
        }
        return key.toString();
    }

    private String lookup(String key) {
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }
}
