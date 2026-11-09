package dev.overlook.vercel.error;

public final class NotLoggedIn extends VercelException {

    public NotLoggedIn() {
        super("NOT_LOGGED_IN",
                "A Vercel CLI não está autenticada.",
                "Rode `vercel login` no terminal e clique em \"Tentar de novo\".");
    }
}
