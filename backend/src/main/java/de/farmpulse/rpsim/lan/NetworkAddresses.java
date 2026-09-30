package de.farmpulse.rpsim.lan;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Roadmap V3 R3-N1 / R3-N3: classifies the sender address of a request and lists the addresses of this computer in
 * the home network. Only IP literals are parsed (no DNS lookup).
 */
public final class NetworkAddresses {

    /** Where a request comes from. */
    public enum Origin {
        /** The gaming PC itself (127.0.0.0/8, ::1): always allowed, never a PIN. */
        LOOPBACK,
        /** A device in the home network (site-local, link-local, IPv6 ULA fc00::/7): only with the switch on. */
        PRIVATE,
        /** Everything else (internet): always 403. */
        PUBLIC
    }

    private NetworkAddresses() {
    }

    public static Origin classify(String remoteAddr) {
        InetAddress a = parse(remoteAddr);
        if (a == null) {
            return Origin.PUBLIC;
        }
        if (a.isLoopbackAddress()) {
            return Origin.LOOPBACK;
        }
        if (a.isSiteLocalAddress() || a.isLinkLocalAddress() || uniqueLocal(a)) {
            return Origin.PRIVATE;
        }
        return Origin.PUBLIC;
    }

    public static boolean isLoopback(String remoteAddr) {
        return classify(remoteAddr) == Origin.LOOPBACK;
    }

    /** IPv6 unique local address fc00::/7. */
    static boolean uniqueLocal(InetAddress a) {
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
    }

    /** Parses an IP literal (IPv4, IPv6 with optional zone and brackets); null for anything else. */
    static InetAddress parse(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return null;
        }
        String s = remoteAddr.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        boolean literal = s.contains(":") || s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (!literal) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** R3-N3: active, private IPv4 addresses of this computer (java.net.NetworkInterface), sorted. */
    public static List<String> privateIpv4Addresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (SocketException | NullPointerException e) {
            return List.of();
        }
        return out.stream().distinct().sorted().toList();
    }
}
