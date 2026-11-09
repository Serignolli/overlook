package dev.overlook.vercel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.overlook.Fixtures;
import dev.overlook.vercel.error.CliFailure;
import dev.overlook.vercel.error.NotLoggedIn;
import dev.overlook.vercel.error.PlanRequired;
import dev.overlook.vercel.error.RateLimited;
import dev.overlook.vercel.error.VercelException;
import org.junit.jupiter.api.Test;

class CliErrorClassifierTest {

    private final CliErrorClassifier classifier = new CliErrorClassifier(new SecretMasker(null));

    private VercelException stdout(String fixture) {
        return classifier.classify(new CliResult(1, Fixtures.read("errors/" + fixture), ""));
    }

    @Test
    void rateLimit() {
        assertThat(stdout("rate-limited.json")).isInstanceOf(RateLimited.class);
    }

    @Test
    void paymentRequired() {
        assertThat(stdout("payment-required.json")).isInstanceOf(PlanRequired.class)
                .hasMessageContaining("Observability Plus");
    }

    @Test
    void schemaUnauthorizedMeansLogin() {
        assertThat(stdout("schema-unauthorized.json")).isInstanceOf(NotLoggedIn.class);
    }

    @Test
    void noTeamKeepsCliCode() {
        VercelException e = stdout("no-team.json");

        assertThat(e).isInstanceOf(CliFailure.class);
        assertThat(e.code()).isEqualTo("NO_TEAM");
    }

    @Test
    void missingCredentialsOnStderr() {
        VercelException e = classifier.classify(new CliResult(1, "", Fixtures.read("errors/no-credentials.stderr.txt")));

        assertThat(e).isInstanceOf(NotLoggedIn.class);
    }

    @Test
    void unknownFailureNeverLeaksRawStderr() {
        VercelException e = classifier.classify(new CliResult(3, "", "TypeError: boom at /home/u/secret/path.js:1"));

        assertThat(e.getMessage()).doesNotContain("TypeError").doesNotContain("secret").contains("3");
    }
}
