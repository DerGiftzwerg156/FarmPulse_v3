package de.farmpulse.rpsim.desktop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.function.IntPredicate;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Desktop mode (owner decision 2026-10-08): {@code server.port} is the preferred port; when another program uses it,
 * FarmPulse takes the next free port up to {@code rpsim.desktop.port-search-range} above it instead of not starting.
 */
@Component
@ConditionalOnProperty(name = "rpsim.desktop.enabled", havingValue = "true")
public class DesktopPortCustomizer implements WebServerFactoryCustomizer<ConfigurableWebServerFactory>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DesktopPortCustomizer.class);

    private final Environment environment;
    private final RpsimProperties props;

    public DesktopPortCustomizer(Environment environment, RpsimProperties props) {
        this.environment = environment;
        this.props = props;
    }

    /** After Spring Boot's own customizer, which sets {@code server.port}. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void customize(ConfigurableWebServerFactory factory) {
        int preferred = preferredPort();
        if (preferred <= 0) {
            return; // 0 = random port, nothing to search
        }
        String address = environment.getProperty("server.address");
        int port = firstFreePort(preferred, props.getDesktop().getPortSearchRange(), p -> isFree(address, p));
        if (port != preferred) {
            log.warn("Port {} ist belegt - FarmPulse verwendet Port {}", preferred, port);
            factory.setPort(port);
        }
    }

    int preferredPort() {
        return environment.getProperty("server.port", Integer.class, 8080);
    }

    /** The preferred port when free, else the next free one up to {@code range} above; else the preferred one. */
    static int firstFreePort(int preferred, int range, IntPredicate free) {
        for (int port = preferred; port <= Math.min(preferred + Math.max(range, 0), 65535); port++) {
            if (free.test(port)) {
                return port;
            }
        }
        return preferred; // Tomcat then reports the port in use - the start dialog explains it
    }

    private static boolean isFree(String address, int port) {
        try (ServerSocket socket = new ServerSocket(port, 1,
                address == null || address.isBlank() ? null : InetAddress.getByName(address))) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
