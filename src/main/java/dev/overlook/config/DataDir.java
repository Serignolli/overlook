package dev.overlook.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Pasta de dados do usuário: {@code ~/.overlook/}, ou {@code %APPDATA%\overlook\} no Windows. */
public final class DataDir {

    private DataDir() {
    }

    public static Path resolve(Map<String, String> env, String osName, String userHome) {
        String override = env.get("OVERLOOK_HOME");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        if (osName.toLowerCase(Locale.ROOT).contains("win")) {
            String appData = env.get("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "overlook");
            }
        }
        return Path.of(userHome, ".overlook");
    }

    public static Path system() {
        return resolve(System.getenv(), System.getProperty("os.name"), System.getProperty("user.home"));
    }

    public static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir.resolve("logs"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível criar " + dir, e);
        }
    }
}
