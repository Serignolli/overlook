package dev.overlook.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {

    @TempDir
    Path dir;

    @Test
    void defaultsWhenMissing() {
        AppConfig c = new ConfigStore(dir.resolve("config.json")).load();

        assertThat(c).isEqualTo(AppConfig.defaults());
        assertThat(c.port()).isEqualTo(7878);
        assertThat(c.history().enabled()).isFalse();
        assertThat(c.productionOnly()).isTrue();
    }

    @Test
    void roundTripsAndIgnoresUnknownKeys() throws IOException {
        Path file = dir.resolve("config.json");
        ConfigStore store = new ConfigStore(file);
        store.save(AppConfig.defaults().withPort(9000));
        Files.writeString(file, Files.readString(file).replaceFirst("\\{", "{\"futureKey\": 1,"));

        AppConfig loaded = new ConfigStore(file).load();

        assertThat(loaded.port()).isEqualTo(9000);
    }

    @Test
    void validatesLimits() {
        ConfigStore store = new ConfigStore(dir.resolve("config.json"));
        AppConfig d = AppConfig.defaults();

        assertThatThrownBy(() -> store.save(d.withPort(80))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(new AppConfig(7878, "personal", "7d", true, null, d.history(),
                new AppConfig.AutoRefresh(true, 10), true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(new AppConfig(7878, "personal", "7d", true, null,
                new AppConfig.History(false, 5), d.autoRefresh(), true))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dataDirFollowsPlatform() {
        assertThat(DataDir.resolve(Map.of("APPDATA", "C:\\Users\\u\\AppData\\Roaming"), "Windows 11", "C:\\Users\\u"))
                .isEqualTo(Path.of("C:\\Users\\u\\AppData\\Roaming", "overlook"));
        assertThat(DataDir.resolve(Map.of(), "Linux", "/home/u")).isEqualTo(Path.of("/home/u", ".overlook"));
        assertThat(DataDir.resolve(Map.of("OVERLOOK_HOME", "/tmp/x"), "Linux", "/home/u")).isEqualTo(Path.of("/tmp/x"));
    }
}
