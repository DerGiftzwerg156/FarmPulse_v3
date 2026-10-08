package de.farmpulse.rpsim.desktop;

import java.awt.AWTError;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import de.farmpulse.rpsim.config.RpsimProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Desktop mode, once the server is ready: writes the URL for a second start, shows the tray icon (open, log, quit)
 * and opens the browser (docs/architecture/windows-installer.md).
 */
@Component
@ConditionalOnProperty(name = "rpsim.desktop.enabled", havingValue = "true")
public class DesktopIntegration implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DesktopIntegration.class);

    private final RpsimProperties props;
    private final DesktopPortCustomizer ports;
    private final DesktopLauncher.DesktopFiles files;
    private TrayIcon trayIcon;

    public DesktopIntegration(RpsimProperties props, DesktopPortCustomizer ports) {
        this.props = props;
        this.ports = ports;
        this.files = new DesktopLauncher.DesktopFiles(Path.of(props.getDesktop().getDataDir()));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ConfigurableApplicationContext context = event.getApplicationContext();
        if (!(context instanceof WebServerApplicationContext web)) {
            return;
        }
        int port = web.getWebServer().getPort();
        String url = "http://localhost:" + port + "/";
        writeUrl(url);
        try {
            showTrayIcon(context, url, port);
        } catch (RuntimeException | AWTError | LinkageError e) { // no desktop session: run without tray icon
            log.warn("No tray icon ({}) - FarmPulse keeps running: {}", e.getClass().getSimpleName(), url);
        }
        log.info("FarmPulse läuft im Hintergrund: {}", url);
        if (props.getDesktop().isOpenBrowser()) {
            DesktopShell.browse(url);
        }
    }

    private void writeUrl(String url) {
        try {
            Files.createDirectories(files.dataDir());
            Files.writeString(files.urlFile(), url, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not write {}: {}", files.urlFile(), e.getMessage());
        }
    }

    private void showTrayIcon(ConfigurableApplicationContext context, String url, int port) {
        if (!SystemTray.isSupported()) {
            log.info("No system tray - FarmPulse is stopped by closing the process");
            return;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("FarmPulse öffnen");
        open.addActionListener(e -> DesktopShell.browse(url));
        MenuItem logFile = new MenuItem("Protokoll öffnen");
        logFile.addActionListener(e -> DesktopShell.open(Files.exists(files.logFile())
                ? files.logFile().toFile() : files.logFile().getParent().toFile()));
        MenuItem quit = new MenuItem("Beenden");
        quit.addActionListener(e -> quit(context));
        menu.add(open);
        menu.add(logFile);
        menu.addSeparator();
        menu.add(quit);

        TrayIcon icon = new TrayIcon(trayImage(), "FarmPulse - " + url, menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> DesktopShell.browse(url)); // double click
        try {
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
        } catch (AWTException e) {
            log.warn("Could not show the tray icon: {}", e.getMessage());
            return;
        }
        int preferred = ports.preferredPort();
        if (preferred > 0 && port != preferred) {
            icon.displayMessage("FarmPulse",
                    "Port " + preferred + " ist belegt - FarmPulse läuft jetzt auf Port " + port
                            + ". Die Adresse für das Tablet hat sich dadurch geändert.",
                    TrayIcon.MessageType.WARNING);
        }
    }

    /** A thread of its own: closing the context from the AWT event thread would wait for that thread. */
    private static void quit(ConfigurableApplicationContext context) {
        Thread stop = new Thread(() -> System.exit(SpringApplication.exit(context)), "farmpulse-quit");
        stop.start();
    }

    /** The web app's icon; a plain "F" when it is missing. */
    private Image trayImage() {
        String dir = props.getWeb().getStaticDir();
        if (dir != null && !dir.isBlank()) {
            try {
                Image image = ImageIO.read(Path.of(dir, "icon-192.png").toFile());
                if (image != null) {
                    return image;
                }
            } catch (IOException e) {
                // fallback below
            }
        }
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x0B0F0D));
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.setColor(new Color(0x38B000));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        g.drawString("F", 9, 25);
        g.dispose();
        return image;
    }

    @PreDestroy
    void shutdown() {
        files.deleteUrl();
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (RuntimeException | AWTError e) {
                // the process ends anyway
            }
        }
    }
}
