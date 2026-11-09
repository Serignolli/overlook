package dev.overlook.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Lê e grava {@code config.json}. Flags de linha de comando não são persistidas. */
public final class ConfigStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private volatile AppConfig current;

    public ConfigStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized AppConfig load() {
        if (Files.notExists(file)) {
            current = AppConfig.defaults();
            return current;
        }
        try {
            AppConfig loaded = MAPPER.readValue(file.toFile(), AppConfig.class).withDefaults();
            try {
                current = loaded.validate();
            } catch (IllegalArgumentException e) {
                current = AppConfig.defaults();
            }
            return current;
        } catch (IOException e) {
            throw new UncheckedIOException("config.json inválido: " + file, e);
        }
    }

    public AppConfig current() {
        AppConfig c = current;
        return c == null ? load() : c;
    }

    public synchronized AppConfig save(AppConfig config) {
        AppConfig valid = config.withDefaults().validate();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), valid);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar " + file, e);
        }
        current = valid;
        return valid;
    }
}
