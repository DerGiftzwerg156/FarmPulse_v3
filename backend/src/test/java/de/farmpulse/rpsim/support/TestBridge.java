package de.farmpulse.rpsim.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Helpers to drive the bridge folder in integration tests, incl. running the real bridge simulator. */
public final class TestBridge {

    private TestBridge() {
    }

    public static Path newDir() {
        try {
            return Files.createTempDirectory("rpsim-bridge-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path simulatorCli() {
        return Path.of("..", "tools", "bridge-simulator", "src", "cli.js").toAbsolutePath().normalize();
    }

    /** True when node and the simulator dependencies are available (otherwise simulator tests are skipped). */
    public static boolean simulatorAvailable() {
        if (!Files.exists(simulatorCli())
                || !Files.isDirectory(Path.of("..", "tools", "bridge-simulator", "node_modules", "ajv"))) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("node", "--version").start();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Runs one simulator cycle (--once): export + process instructions + ack. */
    public static String runSimulatorOnce(Path dir, String scenario, String savegameId, String... extra) {
        List<String> cmd = new ArrayList<>(List.of("node", simulatorCli().toString(), "--once", "--dir", dir.toString(),
                "--scenario", scenario, "--savegame-id", savegameId, "--control-port", "0"));
        cmd.addAll(List.of(extra));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException("simulator failed: " + out);
            }
            return out;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
