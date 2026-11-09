package dev.overlook.vercel.error;

/** Erro da CLI com código conhecido mas sem tratamento específico na UI. */
public final class CliFailure extends VercelException {

    public CliFailure(String code, String message) {
        super(code == null ? "CLI_ERROR" : code,
                message == null || message.isBlank() ? "A Vercel CLI retornou um erro." : message,
                null);
    }
}
