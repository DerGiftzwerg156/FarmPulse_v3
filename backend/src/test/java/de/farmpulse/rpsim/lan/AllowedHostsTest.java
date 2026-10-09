package de.farmpulse.rpsim.lan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.config.RpsimProperties;
import org.junit.jupiter.api.Test;

/** Review 10/2026 Phase 0.2/0.3 (S-2): host names safe against DNS rebinding. */
class AllowedHostsTest {

    private static AllowedHosts hosts(String... extra) {
        RpsimProperties props = new RpsimProperties();
        props.getWeb().setAllowedHosts(List.of(extra));
        return new AllowedHosts(props);
    }

    @Test
    void hostOfHeader() {
        assertThat(AllowedHosts.hostOfHeader("localhost:8080")).isEqualTo("localhost");
        assertThat(AllowedHosts.hostOfHeader("localhost")).isEqualTo("localhost");
        assertThat(AllowedHosts.hostOfHeader("192.168.178.20:8080")).isEqualTo("192.168.178.20");
        assertThat(AllowedHosts.hostOfHeader("[::1]:8080")).isEqualTo("::1");
        assertThat(AllowedHosts.hostOfHeader("[fe80::1]")).isEqualTo("fe80::1");
        assertThat(AllowedHosts.hostOfHeader("[::1")).isNull();
    }

    @Test
    void localhostIpLiteralsTheOwnNameAndConfiguredNamesAreAllowed() {
        AllowedHosts h = hosts("Mein-PC.fritz.box");
        assertThat(h.allowedHostHeader("localhost:8080")).isTrue();
        assertThat(h.allowedHostHeader("LOCALHOST")).isTrue();
        assertThat(h.allowedHostHeader("127.0.0.1:8080")).isTrue();
        assertThat(h.allowedHostHeader("[::1]:8080")).isTrue();
        assertThat(h.allowedHostHeader("192.168.178.20:8080")).isTrue();
        assertThat(h.allowedHostHeader("[fd00::20]:8080")).isTrue();
        assertThat(h.allowedHostHeader("mein-pc.fritz.box:8080")).isTrue();
        String own = AllowedHosts.ownHostName();
        if (own != null) {
            assertThat(h.allowedHostHeader(own + ":8080")).isTrue();
        }
    }

    @Test
    void foreignNamesAreRefused() {
        AllowedHosts h = hosts();
        assertThat(h.allowedHostHeader("evil.example:8080")).isFalse();
        assertThat(h.allowedHostHeader("localhost.evil.example")).isFalse();
        assertThat(h.allowedHostHeader("127.0.0.1.evil.example")).isFalse();
        assertThat(h.allowedHostHeader("mein-pc.fritz.box")).isFalse(); // not configured
        assertThat(h.allowedHostHeader("cafe.de")).isFalse(); // hex characters, but no IP literal
        assertThat(h.allowedHostHeader("zz:zz:zz")).isFalse();
        assertThat(h.allowedHostHeader("")).isFalse();
    }

    @Test
    void origins() {
        AllowedHosts h = hosts();
        assertThat(h.allowedOrigin("http://localhost:4200")).isTrue();
        assertThat(h.allowedOrigin("http://127.0.0.1:8080")).isTrue();
        assertThat(h.allowedOrigin("http://[::1]:8080")).isTrue();
        assertThat(h.allowedOrigin("http://192.168.178.5:8080")).isTrue();
        assertThat(h.allowedOrigin("https://evil.example")).isFalse();
        assertThat(h.allowedOrigin("http://evil.example:8080")).isFalse();
        assertThat(h.allowedOrigin("null")).isFalse();
        assertThat(h.allowedOrigin("not a uri")).isFalse();
        assertThat(h.allowedOrigin("")).isFalse();
    }
}
