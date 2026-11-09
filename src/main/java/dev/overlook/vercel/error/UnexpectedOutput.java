package dev.overlook.vercel.error;

public final class UnexpectedOutput extends VercelException {

    public UnexpectedOutput(String detail, Throwable cause) {
        super("UNEXPECTED_OUTPUT",
                "O formato de resposta da Vercel CLI mudou (" + detail + ").",
                "Abra uma issue informando a versão da CLI. A saída bruta, mascarada, está no log local.",
                cause);
    }
}
