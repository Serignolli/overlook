package dev.overlook.vercel.error;

public final class CliOutdated extends VercelException {

    private final String currentVersion;
    private final String minimumVersion;

    public CliOutdated(String currentVersion, String minimumVersion) {
        super("CLI_OUTDATED",
                "Vercel CLI " + currentVersion + " não tem `vercel metrics` com Web Analytics (mínimo: "
                        + minimumVersion + ").",
                "Atualize com `npm i -g vercel@latest`.");
        this.currentVersion = currentVersion;
        this.minimumVersion = minimumVersion;
    }

    public String currentVersion() {
        return currentVersion;
    }

    public String minimumVersion() {
        return minimumVersion;
    }
}
