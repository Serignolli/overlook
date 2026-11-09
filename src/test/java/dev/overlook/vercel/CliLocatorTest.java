package dev.overlook.vercel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.overlook.vercel.error.CliNotFound;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliLocatorTest {

    @TempDir
    Path dir;

    @Test
    void windowsCmdShimRunsNodeDirectly() throws IOException {
        Files.writeString(dir.resolve("vercel.cmd"), "@echo off");
        Files.writeString(dir.resolve("node.exe"), "");
        Path script = dir.resolve("node_modules/vercel/dist/vc.js");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "");

        List<String> cmd = new CliLocator(Map.of("Path", dir.toString(), "PATHEXT", ".EXE;.CMD"), true).locate(null);

        assertThat(cmd).containsExactly(dir.resolve("node.exe").toString(), script.toString());
    }

    @Test
    void unixNodeScriptRunsWithSiblingNode() throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path cli = Files.writeString(bin.resolve("vercel"), "#!/usr/bin/env node\nrequire('./x')\n");
        Path node = Files.writeString(bin.resolve("node"), "");
        cli.toFile().setExecutable(true);
        node.toFile().setExecutable(true);

        List<String> cmd = new CliLocator(Map.of("PATH", bin.toString()), false).locate(null);

        assertThat(cmd).containsExactly(node.toString(), cli.toRealPath().toString());
    }

    @Test
    void searchesNodeManagerFoldersOutsidePath() {
        List<Path> dirs = new CliLocator(Map.of("PATH", "/usr/bin", "HOME", "/home/u"), false).searchDirs();

        assertThat(dirs).contains(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"), Path.of("/home/u", ".volta/bin"));
        assertThat(dirs.get(0)).isEqualTo(Path.of("/usr/bin"));
    }

    @Test
    void configuredPathWins() throws IOException {
        Path exe = Files.writeString(dir.resolve("my-vercel"), "");

        assertThat(new CliLocator(Map.of(), false).locate(exe.toString())).containsExactly(exe.toString());
    }

    @Test
    void missingCliIsTypedError() {
        assertThatThrownBy(() -> new CliLocator(Map.of("PATH", dir.toString()), false).locate(null))
                .isInstanceOf(CliNotFound.class);
        assertThatThrownBy(() -> new CliLocator(Map.of(), false).locate(dir.resolve("nope").toString()))
                .isInstanceOf(CliNotFound.class);
    }
}
