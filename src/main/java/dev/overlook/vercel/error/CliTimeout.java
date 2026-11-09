package dev.overlook.vercel.error;

public final class CliTimeout extends VercelException {

    public CliTimeout() {
        super("TIMEOUT", "A Vercel demorou para responder.", "Tente de novo ou reduza o período.");
    }
}
