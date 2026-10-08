package de.farmpulse.rpsim.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.PortInUseException;

/** Windows installer: defaults and messages of the desktop start (docs/architecture/windows-installer.md). */
class DesktopLauncherTest {

    @TempDir
    Path tmp;

    @Test
    void defaultsProdDesktopDataFolderAndWebFolderNextToTheJar() throws IOException {
        Path app = Files.createDirectories(tmp.resolve("app"));
        Files.createDirectories(app.resolve("web"));
        Files.writeString(app.resolve("web/index.html"), "<html>");
        Properties p = new Properties();
        p.setProperty("user.home", tmp.resolve("home").toString());
        p.setProperty("java.class.path", app.resolve("rpsim-backend.jar").toString());

        DesktopLauncher.applyDefaults(p);

        assertThat(p.getProperty(DesktopLauncher.PROFILES)).isEqualTo("prod,desktop");
        assertThat(p.getProperty(DesktopLauncher.DATA_DIR)).endsWith("/.rpsim").doesNotContain("\\");
        assertThat(p.getProperty(DesktopLauncher.STATIC_DIR)).isEqualTo(app.resolve("web").toString());
    }

    @Test
    void explicitValuesWin() {
        Properties p = new Properties();
        p.setProperty("user.home", tmp.toString());
        p.setProperty("java.class.path", tmp.resolve("no-web-here.jar").toString());
        p.setProperty(DesktopLauncher.PROFILES, "prod,desktop,extra");
        p.setProperty(DesktopLauncher.DATA_DIR, "D:\\FarmPulse");
        p.setProperty(DesktopLauncher.STATIC_DIR, "D:/web");

        DesktopLauncher.applyDefaults(p);

        assertThat(p.getProperty(DesktopLauncher.PROFILES)).isEqualTo("prod,desktop,extra");
        assertThat(p.getProperty(DesktopLauncher.DATA_DIR)).isEqualTo("D:/FarmPulse");
        assertThat(p.getProperty(DesktopLauncher.STATIC_DIR)).isEqualTo("D:/web");
    }

    @Test
    void noWebFolderNoStaticDir() {
        Properties p = new Properties();
        p.setProperty("user.home", tmp.toString());
        p.setProperty("java.class.path", tmp.resolve("rpsim-backend.jar").toString());
        DesktopLauncher.applyDefaults(p);
        assertThat(p.getProperty(DesktopLauncher.STATIC_DIR)).isNull();
    }

    @Test
    void messagesNameTheUsualCauses() {
        Path log = tmp.resolve("logs/farmpulse.log");
        String dbInUse = DesktopLauncher.startFailureMessage(new IllegalStateException("context",
                new RuntimeException("Database may be already in use: \"rpsim.mv.db\" [90020-224]")), log);
        assertThat(dbInUse).contains("start.bat").contains(log.toString());
        assertThat(DesktopLauncher.startFailureMessage(new PortInUseException(8080), log)).contains("Port");
        assertThat(DesktopLauncher.startFailureMessage(new IllegalStateException("boom"), log))
                .contains("unerwarteter Fehler").contains("boom");
    }

    @Test
    void urlFileOnlyAcceptsHttpUrls() throws IOException {
        var files = new DesktopLauncher.DesktopFiles(tmp);
        assertThat(files.readUrl()).isNull();
        Files.writeString(files.urlFile(), "http://localhost:8081/\n");
        assertThat(files.readUrl()).isEqualTo("http://localhost:8081/");
        Files.writeString(files.urlFile(), "file:///etc/passwd");
        assertThat(files.readUrl()).isNull();
        files.deleteUrl();
        assertThat(files.urlFile()).doesNotExist();
    }

    @Test
    void secondLockIsRefusedUntilTheFirstIsReleased() throws IOException {
        Path file = tmp.resolve("sub/desktop.lock");
        try (InstanceLock first = InstanceLock.tryAcquire(file)) {
            assertThat(first).isNotNull();
            assertThat(InstanceLock.tryAcquire(file)).isNull();
        }
        try (InstanceLock again = InstanceLock.tryAcquire(file)) {
            assertThat(again).isNotNull();
        }
    }
}
