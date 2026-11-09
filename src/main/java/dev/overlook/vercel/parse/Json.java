package dev.overlook.vercel.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.overlook.vercel.error.UnexpectedOutput;

/** Leitura tolerante da saída JSON da CLI: campos desconhecidos são ignorados. */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }

    static JsonNode read(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new UnexpectedOutput(what + ": saída vazia", null);
        }
        try {
            return MAPPER.readTree(extractJson(text));
        } catch (JsonProcessingException e) {
            throw new UnexpectedOutput(what + ": JSON inválido", e);
        }
    }

    /**
     * A CLI pode imprimir avisos antes do JSON (ex.: aviso de atualização). Começa a ler
     * no primeiro caractere que abre um objeto ou array no início de uma linha.
     */
    static String extractJson(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        for (String marker : new String[] {"\n{", "\n["}) {
            int idx = trimmed.indexOf(marker);
            if (idx >= 0) {
                return trimmed.substring(idx + 1);
            }
        }
        return trimmed;
    }

    static JsonNode required(JsonNode node, String field, String what) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new UnexpectedOutput(what + ": campo obrigatório ausente '" + field + "'", null);
        }
        return value;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
