package de.farmpulse.rpsim.lan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Technical review 10/2026, Phase 0.2/0.3 (S-2): DNS rebinding protection, runs before the {@link LanAccessFilter}.
 * A web page whose domain is re-pointed at this computer reaches the backend from loopback, so the sender address
 * alone proves nothing.
 * <ul>
 *   <li>{@code Host} header (0.2): only {@link AllowedHosts}; requests without the header (no browser) pass;</li>
 *   <li>{@code Origin} header of writing requests (0.3, everything but GET/HEAD/OPTIONS/TRACE): if present, it must
 *   name an allowed host as well.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostHeaderFilter extends OncePerRequestFilter {

    static final String HOST_FORBIDDEN = "{\"code\":\"HOST_FORBIDDEN\",\"message\":\"Unbekannte Adresse - FarmPulse bitte "
            + "über localhost, die IP-Adresse oder den Rechnernamen öffnen\",\"fields\":{}}";
    static final String ORIGIN_FORBIDDEN = "{\"code\":\"ORIGIN_FORBIDDEN\",\"message\":\"Anfrage von einer fremden "
            + "Webseite abgelehnt\",\"fields\":{}}";
    private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AllowedHosts hosts;

    public HostHeaderFilter(AllowedHosts hosts) {
        this.hosts = hosts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String host = req.getHeader(HttpHeaders.HOST);
        if (host != null && !hosts.allowedHostHeader(host)) {
            write(res, HOST_FORBIDDEN);
            return;
        }
        String origin = req.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !READING.contains(req.getMethod()) && !hosts.allowedOrigin(origin)) {
            write(res, ORIGIN_FORBIDDEN);
            return;
        }
        chain.doFilter(req, res);
    }

    private static void write(HttpServletResponse res, String body) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(body);
    }
}
