package dev.overlook.vercel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.overlook.vercel.error.CliNotFound;
import dev.overlook.vercel.error.CliTimeout;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliRunnerTest {

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("VERCEL_TOKEN", "test-token-value-123");
        env.put("OVERLOOK_TEST_OTHER_SECRET", "nope");
        return env;
    }

    private static CliRunner runner(Duration timeout) {
        return new CliRunner(FakeCli.command(), env(), timeout, new SecretMasker("test-token-value-123"));
    }

    @Test
    void argumentsStaySeparateAndAreNeverJoinedIntoAShellString() {
        CliRunner r = new CliRunner(List.of("vercel"));

        assertThat(r.buildCommand(List.of("metrics", "vercel.analytics.page_view.count", "--since", "2026-10-01T00:00:00Z")))
                .containsExactly("vercel", "metrics", "vercel.analytics.page_view.count", "--since", "2026-10-01T00:00:00Z");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--token", "--token=abc", "--TOKEN", "-t", "a b", "x;rm", "a&b", "a|b", "$(id)", "`id`",
            "\"quoted\"", "line\nbreak", "100%", "a^b", "<in", "(x)"})
    void rejectsTokensAndShellMetacharacters(String arg) {
        CliRunner r = new CliRunner(List.of("vercel"));

        assertThatThrownBy(() -> r.buildCommand(List.of("metrics", arg))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void environmentIsAllowlisted() {
        Map<String, String> child = CliRunner.childEnvironment(Map.of(
                "PATH", "/bin", "VERCEL_TOKEN", "t", "AWS_SECRET_ACCESS_KEY", "s", "HOME", "/home/u", "SystemRoot", "C:\\Windows"));

        assertThat(child).containsEntry("PATH", "/bin")
                .containsEntry("VERCEL_TOKEN", "t")
                .containsEntry("HOME", "/home/u")
                .containsEntry("SystemRoot", "C:\\Windows")
                .containsEntry("NO_COLOR", "1")
                .containsEntry("VERCEL_TELEMETRY_DISABLED", "1")
                .doesNotContainKey("AWS_SECRET_ACCESS_KEY");
    }

    @Test
    void executableFolderGoesFirstInChildPath() {
        String exe = Path.of(System.getProperty("java.io.tmpdir"), "nodedir", "node").toAbsolutePath().toString();
        Map<String, String> child = CliRunner.childEnvironment(Map.of("PATH", "/bin"), exe);

        assertThat(child.get("PATH")).startsWith(Path.of(exe).getParent().toString() + java.io.File.pathSeparator)
                .endsWith("/bin");
    }

    @Test
    void passesTokenOnlyThroughEnvironment() {
        CliResult result = runner(Duration.ofSeconds(30)).run(List.of("echo-args", "--scope", "acme"));

        assertThat(result.ok()).isTrue();
        assertThat(result.stdout().lines()).containsSubsequence("echo-args", "--scope", "acme")
                .contains("ENV_TOKEN=true", "ENV_OTHER=false", "ENV_NO_COLOR=1")
                .noneMatch(l -> l.contains("test-token-value-123"));
    }

    @Test
    void timesOut() {
        assertThatThrownBy(() -> runner(Duration.ofSeconds(2)).run(List.of("sleep"))).isInstanceOf(CliTimeout.class);
    }

    @Test
    void missingExecutableIsCliNotFound() {
        CliRunner r = new CliRunner(List.of("definitely-not-a-real-binary-overlook"), env(), Duration.ofSeconds(5),
                new SecretMasker(null));

        assertThatThrownBy(() -> r.run(List.of("--version"))).isInstanceOf(CliNotFound.class);
    }

    @Test
    void concurrencyIsCapped() {
        assertThat(CliRunner.MAX_CONCURRENT).isEqualTo(2);
        assertThat(CliRunner.DEFAULT_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }
}
