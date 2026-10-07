package de.farmpulse.rpsim.lan;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.springframework.stereotype.Component;

/**
 * Technical review 10/2026, Phase 0.2/0.3 (S-2): which host names a request may address. DNS rebinding needs a domain
 * name the attacker controls, so these are safe: {@code localhost}, every IP literal (IPv4, IPv6), the name of this
 * computer and the names in {@code rpsim.web.allowed-hosts}.
 */
@Component
public class AllowedHosts {

    private final Set<String> names = new TreeSet<>();

    public AllowedHosts(RpsimProperties props) {
        names.add("localhost");
        String own = ownHostName();
        if (own != null) {
            names.add(own.toLowerCase(Locale.ROOT));
        }
        props.getWeb().getAllowedHosts().stream().filter(h -> h != null && !h.isBlank())
                .forEach(h -> names.add(h.strip().toLowerCase(Locale.ROOT)));
    }

    /** Name of this computer (Windows: the computer name), or null. */
    static String ownHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            String env = System.getenv("COMPUTERNAME");
            return env == null || env.isBlank() ? null : env;
        }
    }

    /** Host without port and brackets, e.g. {@code [::1]:8080} -> {@code ::1}. */
    public boolean allowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (names.contains(h)) {
            return true;
        }
        // only characters of IP literals reach the parser, so no header value can trigger a DNS lookup
        return h.matches("[0-9a-f:.]+") && NetworkAddresses.parse(h) != null;
    }

    /** Value of a {@code Host} header ({@code name}, {@code name:port}, {@code [v6]}, {@code [v6]:port}). */
    public boolean allowedHostHeader(String header) {
        return allowed(hostOfHeader(header));
    }

    /** Value of an {@code Origin} header ({@code scheme://host[:port]}); {@code null} (the literal) is refused. */
    public boolean allowedOrigin(String origin) {
        if (origin == null || origin.isBlank() || origin.strip().equals("null")) {
            return false;
        }
        try {
            String host = new URI(origin.strip()).getHost();
            return allowed(host == null ? null : unbracket(host));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    static String hostOfHeader(String header) {
        if (header == null) {
            return null;
        }
        String h = header.strip();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end < 0 ? null : h.substring(1, end);
        }
        int colon = h.indexOf(':');
        if (colon >= 0 && colon == h.lastIndexOf(':')) {
            return h.substring(0, colon);
        }
        return h; // no port, or an unbracketed IPv6 literal
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
