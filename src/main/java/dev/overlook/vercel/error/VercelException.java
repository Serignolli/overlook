package dev.overlook.vercel.error;

/**
 * Falha tipada vinda do gateway da Vercel. A mensagem é segura para mostrar na UI:
 * nunca contém stderr cru nem stack trace.
 */
public abstract class VercelException extends RuntimeException {

    private final String code;
    private final String action;

    protected VercelException(String code, String message, String action) {
        super(message);
        this.code = code;
        this.action = action;
    }

    protected VercelException(String code, String message, String action, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.action = action;
    }

    /** Código estável usado pela API local e pelo frontend. */
    public String code() {
        return code;
    }

    /** O que o usuário pode fazer para resolver, ou {@code null}. */
    public String action() {
        return action;
    }
}
