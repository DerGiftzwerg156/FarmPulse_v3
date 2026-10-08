package de.farmpulse.rpsim.desktop;

import java.awt.AWTError;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Browser, files and dialogs of the desktop mode. Never fails: without a desktop session (server, CI, broken display)
 * it falls back to the system command or the log - FarmPulse itself keeps running.
 */
final class DesktopShell {

    private static final Logger log = LoggerFactory.getLogger(DesktopShell.class);

    private DesktopShell() {
    }

    static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | RuntimeException | AWTError | LinkageError e) { // no desktop session
            log.debug("Desktop.browse failed, trying the system command: {}", e.getMessage());
        }
        if (!run(systemOpenCommand(url))) {
            log.info("FarmPulse im Browser öffnen: {}", url);
        }
    }

    static void open(File file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
                return;
            }
        } catch (IOException | RuntimeException | AWTError | LinkageError e) {
            log.debug("Desktop.open failed, trying the system command: {}", e.getMessage());
        }
        run(systemOpenCommand(file.getAbsolutePath()));
    }

    static void showError(String title, String message) {
        System.err.println(title + ": " + message);
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
        } catch (RuntimeException | AWTError | LinkageError e) {
            // no desktop session - the message is on stderr and in the log
        }
    }

    /** Fallback when {@link Desktop} is not available (e.g. some Linux desktops). */
    static List<String> systemOpenCommand(String target) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", target);
        }
        if (os.contains("mac")) {
            return List.of("open", target);
        }
        return List.of("xdg-open", target);
    }

    private static boolean run(List<String> command) {
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
