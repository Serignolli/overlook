package dev.overlook.vercel;

public record CliResult(int exitCode, String stdout, String stderr) {

    public boolean ok() {
        return exitCode == 0;
    }
}
