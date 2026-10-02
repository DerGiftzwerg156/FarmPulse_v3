package de.farmpulse.rpsim.lan;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** Roadmap V3 R3-N3: logs the addresses of this computer in the home network at start ("Auf dem Tablet öffnen: …"). */
@Component
public class LanAddressLogger implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(LanAddressLogger.class);

    private final LanAccessService lan;

    public LanAddressLogger(LanAccessService lan) {
        this.lan = lan;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        List<String> ips = NetworkAddresses.privateIpv4Addresses();
        if (ips.isEmpty()) {
            log.info("Keine Adresse im Heimnetz gefunden - das Tool ist nur auf diesem PC erreichbar (http://localhost:{})",
                    port);
            return;
        }
        String hint = lan.enabled() ? "" : " (Heimnetz-Zugriff ist aus: Einstellungen -> Tablet & Netzwerk)";
        for (String ip : ips) {
            log.info("Auf dem Tablet öffnen: http://{}:{}{}", ip, port, hint);
        }
    }
}
