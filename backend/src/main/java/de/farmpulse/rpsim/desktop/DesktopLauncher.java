package de.farmpulse.rpsim.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.springframework.boot.SpringApplication;

/**
 * Start of {@code FarmPulse.exe} from the Windows installer (docs/architecture/windows-installer.md): one instance per
 * user, profiles {@code prod,desktop}, no console - start problems are shown in a dialog. Only used when the JVM runs
 * with {@code -Drpsim.desktop.enabled=true}; {@code start.bat}, development and tests start Spring directly.
 */
public final class DesktopLauncher {

    static final String ENABLED = "rpsim.desktop.enabled";
    static final String DATA_DIR = "rpsim.desktop.data-dir";
    static final String PROFILES = "spring.profiles.active";
    static final String STATIC_DIR = "rpsim.web.static-dir";

    /** A second start waits this long for the first one to become ready, then opens its browser tab. */
    private static final Duration WAIT_FOR_RUNNING = Duration.ofSeconds(120);

    /** Held until the JVM ends - the operating system releases the lock with the process. */
    @SuppressWarnings("unused")
    private static InstanceLock lock;

    private DesktopLauncher() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED);
    }

    public static void launch(Class<?> application, String[] args) {
        applyDefaults(System.getProperties());
        DesktopFiles files = new DesktopFiles(Path.of(System.getProperty(DATA_DIR)));
        try {
            lock = InstanceLock.tryAcquire(files.lockFile());
        } catch (IOException e) {
            DesktopShell.showError("FarmPulse konnte nicht starten",
                    "Der Ordner " + files.dataDir() + " ist nicht beschreibbar.\n\nFehler: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (lock == null) {
            showRunningInstance(files);
            return;
        }
        files.deleteUrl(); // left over when the last run was killed
        SpringApplication spring = new SpringApplication(application);
        spring.setHeadless(false); // tray icon, browser and dialogs need AWT
        try {
            spring.run(args);
        } catch (Throwable e) {
            DesktopShell.showError("FarmPulse konnte nicht starten", startFailureMessage(e, files.logFile()));
            System.exit(1);
        }
    }

    /**
     * Defaults of the installed application unless given explicitly (an expert may still pass other values): profiles
     * {@code prod,desktop}, data folder {@code ~/.rpsim} with forward slashes (it is used in {@code file:} imports) and
     * the web folder next to the backend jar ({@code app\web} of the jpackage image).
     */
    static void applyDefaults(Properties system) {
        if (isBlank(system.getProperty(PROFILES))) {
            system.setProperty(PROFILES, "prod,desktop");
        }
        String dataDir = system.getProperty(DATA_DIR);
        if (isBlank(dataDir)) {
            dataDir = Path.of(system.getProperty("user.home"), ".rpsim").toString();
        }
        system.setProperty(DATA_DIR, dataDir.replace('\\', '/'));
        if (isBlank(system.getProperty(STATIC_DIR))) {
            Path web = webDirNextToJar(system.getProperty("java.class.path", ""));
            if (web != null) {
                system.setProperty(STATIC_DIR, web.toString());
            }
        }
    }

    /** The jpackage launcher starts the backend jar from its app folder; the built frontend lies next to it. */
    static Path webDirNextToJar(String classPath) {
        String first = classPath.split(File.pathSeparator, 2)[0];
        if (first.isBlank()) {
            return null;
        }
        Path parent = Path.of(first).toAbsolutePath().getParent();
        Path web = parent == null ? null : parent.resolve("web");
        return web != null && Files.isRegularFile(web.resolve("index.html")) ? web : null;
    }

    /** Second start: no second server - open the running one in the browser (once it is ready). */
    private static void showRunningInstance(DesktopFiles files) {
        long deadline = System.nanoTime() + WAIT_FOR_RUNNING.toNanos();
        while (System.nanoTime() < deadline) {
            String url = files.readUrl();
            if (url != null) {
                DesktopShell.browse(url);
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        DesktopShell.showError("FarmPulse läuft bereits",
                "FarmPulse läuft bereits, ist aber noch nicht bereit.\n\nBitte etwas warten und erneut versuchen. "
                        + "Protokoll: " + files.logFile());
    }

    /** Understandable message for the most common start problems; the details are in the log file. */
    static String startFailureMessage(Throwable error, Path logFile) {
        String hint = "Ein unerwarteter Fehler ist aufgetreten.";
        for (Throwable t = error; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("Database may be already in use") || m.contains("90020")) {
                hint = "Die Spieldaten sind bereits geöffnet. Läuft FarmPulse schon, zum Beispiel über start.bat? "
                        + "Bitte das andere FarmPulse beenden und erneut starten.";
                break;
            }
            if (t.getClass().getSimpleName().equals("PortInUseException")) {
                hint = "Kein freier Port gefunden. Bitte den Port im Setup (Einstellungen neu festlegen) ändern.";
                break;
            }
        }
        return hint + "\n\nFehler: " + rootMessage(error) + "\n\nProtokoll: " + logFile;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The files of the desktop mode in the data folder. */
    record DesktopFiles(Path dataDir) {

        Path lockFile() {
            return dataDir.resolve("desktop.lock");
        }

        Path urlFile() {
            return dataDir.resolve("desktop.url");
        }

        Path logFile() {
            return dataDir.resolve("logs").resolve("farmpulse.log");
        }

        void deleteUrl() {
            try {
                Files.deleteIfExists(urlFile());
            } catch (IOException e) {
                // a stale URL is replaced as soon as the server is ready
            }
        }

        String readUrl() {
            try {
                String url = Files.readString(urlFile()).strip();
                return url.startsWith("http://") ? url : null;
            } catch (IOException e) {
                return null;
            }
        }
    }
}
