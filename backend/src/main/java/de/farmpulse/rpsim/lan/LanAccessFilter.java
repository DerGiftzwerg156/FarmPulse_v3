package de.farmpulse.rpsim.lan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import de.farmpulse.rpsim.lan.NetworkAddresses.Origin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Roadmap V3 R3-N1/N2: decides every request (API, live updates and the web app) by its sender address.
 * <ul>
 *   <li>loopback (the gaming PC): always allowed, never a PIN;</li>
 *   <li>private address (home network): only with the switch "Im Heimnetz erreichbar"; with a PIN set, the API needs
 *   the session cookie of the PIN login (the web app itself, {@code /api/lan/status} and {@code /api/lan/login}
 *   stay reachable so the device can log in);</li>
 *   <li>any other address: always 403 - a port forwarding on the router does not open the tool to the internet.</li>
 * </ul>
 * {@code server.address} stays unset; the sender address is the TCP peer ({@code getRemoteAddr()}), forwarding
 * headers are ignored.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LanAccessFilter extends OncePerRequestFilter {

    static final String FORBIDDEN = "{\"code\":\"LAN_FORBIDDEN\",\"message\":\"Zugriff nur vom Spiele-PC oder - wenn in den "
            + "Einstellungen freigeschaltet - aus dem eigenen Heimnetz\",\"fields\":{}}";
    static final String LOGIN_REQUIRED = "{\"code\":\"LAN_LOGIN_REQUIRED\",\"message\":\"Bitte mit der PIN anmelden\","
            + "\"fields\":{}}";

    private final LanAccessService lan;

    public LanAccessFilter(LanAccessService lan) {
        this.lan = lan;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Origin origin = NetworkAddresses.classify(req.getRemoteAddr());
        if (origin == Origin.LOOPBACK) {
            chain.doFilter(req, res);
            return;
        }
        if (origin == Origin.PUBLIC || !lan.enabled()) {
            write(res, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN);
            return;
        }
        if (!lan.pinSet() || !needsSession(path(req)) || lan.sessionValid(cookie(req, lan.cookieName()))) {
            chain.doFilter(req, res);
            return;
        }
        write(res, HttpServletResponse.SC_UNAUTHORIZED, LOGIN_REQUIRED);
    }

    private static String path(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }

    /** Data needs the session; the web app itself and the login endpoints do not. */
    static boolean needsSession(String path) {
        if (path.equals("/api/lan/status") || path.equals("/api/lan/login")) {
            return false;
        }
        return path.startsWith("/api/") || path.startsWith("/actuator") || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui");
    }

    static String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie c : req.getCookies()) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static void write(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(body);
    }
}
