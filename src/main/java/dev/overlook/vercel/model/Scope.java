package dev.overlook.vercel.model;

/**
 * Conta pessoal ou time da Vercel, como listado por {@code vercel teams ls}.
 *
 * @param id      ID da Vercel (times começam com {@code team_})
 * @param slug    valor aceito por {@code --scope}
 * @param name    nome de exibição
 * @param current se é o escopo atualmente selecionado na CLI
 */
public record Scope(String id, String slug, String name, String plan, boolean current) {

    public boolean personal() {
        return id == null || !id.startsWith("team_");
    }
}
