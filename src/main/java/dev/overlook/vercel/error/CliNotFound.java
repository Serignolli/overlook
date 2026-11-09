package dev.overlook.vercel.error;

public final class CliNotFound extends VercelException {

    public CliNotFound(String detail) {
        super("CLI_NOT_FOUND",
                "Vercel CLI não encontrada" + (detail == null ? "." : ": " + detail),
                "Instale com `npm i -g vercel` ou informe o caminho da CLI nas configurações.");
    }
}
