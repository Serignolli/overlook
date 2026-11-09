package dev.overlook.vercel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretMaskerTest {

    private static final String TOKEN = "AbC123dEf456GhI789jKl012";

    @Test
    void masksKnownSecretAnywhere() {
        String out = new SecretMasker("my-secret-value").mask("falhou com my-secret-value no meio");

        assertThat(out).doesNotContain("my-secret-value").contains(SecretMasker.MASK);
    }

    @Test
    void masksKeyedTokens() {
        SecretMasker m = new SecretMasker(null);

        assertThat(m.mask("token=abc")).isEqualTo("token=****");
        assertThat(m.mask("{\"token\": \"abc\"}")).doesNotContain("abc");
        assertThat(m.mask("Authorization: Bearer xyz.abc")).doesNotContain("xyz.abc");
        assertThat(m.mask("vercel ls --token " + TOKEN)).doesNotContain(TOKEN);
        assertThat(m.mask("VERCEL_TOKEN=shorty")).doesNotContain("shorty");
    }

    @Test
    void masksOpaqueTokenLikeStrings() {
        assertThat(new SecretMasker(null).mask("erro " + TOKEN + " fim")).isEqualTo("erro **** fim");
    }

    @Test
    void keepsOrdinaryText() {
        String text = "Project \"site\" was not found in team \"acme\" (exit 1)";

        assertThat(new SecretMasker(null).mask(text)).isEqualTo(text);
    }
}
