package dev.overlook.vercel;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mascara qualquer coisa com cara de token antes de ir para o log.
 * Erra para o lado de mascarar demais: IDs longos também somem do log.
 */
public final class SecretMasker {

    static final String MASK = "****";

    private static final List<Pattern> KEYED = List.of(
            // token=..., "token": "...", Authorization: Bearer ...
            Pattern.compile("(?i)(\"?(?:token|access_?token|refresh_?token|authorization|secret|password)\"?\\s*[:=]\\s*\"?)(?:bearer\\s+)?([^\\s\"',}]+)"),
            Pattern.compile("(?i)(bearer\\s+)([^\\s\"',}]+)"),
            Pattern.compile("(?i)(--token[=\\s]+)(\\S+)"));

    /** Sequências longas de caracteres de token que misturam letras e dígitos. */
    private static final Pattern OPAQUE =
            Pattern.compile("\\b(?=[A-Za-z0-9_-]*[0-9])(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{24,}\\b");

    private final String knownSecret;

    public SecretMasker() {
        this(System.getenv("VERCEL_TOKEN"));
    }

    public SecretMasker(String knownSecret) {
        this.knownSecret = knownSecret == null || knownSecret.length() < 8 ? null : knownSecret;
    }

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        if (knownSecret != null) {
            out = out.replace(knownSecret, MASK);
        }
        for (Pattern p : KEYED) {
            out = p.matcher(out).replaceAll(r -> Matcher.quoteReplacement(r.group(1) + MASK));
        }
        return OPAQUE.matcher(out).replaceAll(MASK);
    }
}
