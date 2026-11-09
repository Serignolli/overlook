package dev.overlook.vercel.model;

/**
 * Estado da Vercel CLI para {@code GET /api/health}.
 *
 * @param errorCode código do problema mais importante, ou {@code null} se está tudo certo
 */
public record CliHealth(
        boolean installed,
        String version,
        String minimumVersion,
        boolean outdated,
        boolean loggedIn,
        Scope currentScope,
        boolean demo,
        String errorCode,
        String errorMessage,
        String errorAction) {

    public boolean ok() {
        return errorCode == null;
    }
}
