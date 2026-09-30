package de.farmpulse.rpsim.lan;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.lan.NetworkAddresses.Origin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Roadmap V3 R3-N1: the sender address decides - loopback, home network or internet. */
class NetworkAddressesTest {

    @ParameterizedTest
    @CsvSource({
            "127.0.0.1, LOOPBACK", "127.5.0.1, LOOPBACK", "::1, LOOPBACK", "0:0:0:0:0:0:0:1, LOOPBACK",
            "192.168.178.20, PRIVATE", "10.0.0.7, PRIVATE", "172.16.4.1, PRIVATE", "172.31.255.1, PRIVATE",
            "169.254.10.3, PRIVATE", "fe80::1, PRIVATE", "fd12:3456::1, PRIVATE", "fc00::5, PRIVATE",
            "::ffff:192.168.1.5, PRIVATE", "[fd00::2], PRIVATE",
            "8.8.8.8, PUBLIC", "172.32.0.1, PUBLIC", "2001:db8::1, PUBLIC", "100.64.0.1, PUBLIC",
            "example.org, PUBLIC", "'', PUBLIC"
    })
    void classifiesTheSenderAddress(String address, Origin expected) {
        assertThat(NetworkAddresses.classify(address)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void listsOnlyPrivateIpv4Addresses() {
        assertThat(NetworkAddresses.privateIpv4Addresses())
                .allSatisfy(ip -> assertThat(NetworkAddresses.classify(ip)).isEqualTo(Origin.PRIVATE))
                .allSatisfy(ip -> assertThat(ip).doesNotContain(":"));
    }
}
