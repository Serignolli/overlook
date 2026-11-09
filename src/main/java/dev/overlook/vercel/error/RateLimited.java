package dev.overlook.vercel.error;

public final class RateLimited extends VercelException {

    private final int retryAfterSeconds;

    public RateLimited(int retryAfterSeconds) {
        super("RATE_LIMITED",
                "Limite de consultas da Vercel atingido.",
                "Tentando de novo em " + retryAfterSeconds + " s.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public RateLimited withRetryAfter(int seconds) {
        return new RateLimited(seconds);
    }
}
