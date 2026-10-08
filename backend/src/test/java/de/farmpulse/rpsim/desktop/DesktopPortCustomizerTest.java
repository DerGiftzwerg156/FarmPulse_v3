package de.farmpulse.rpsim.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** Owner decision 2026-10-08: a port in use -> automatically the next free port. */
class DesktopPortCustomizerTest {

    @Test
    void preferredPortWhenFree() {
        assertThat(DesktopPortCustomizer.firstFreePort(8080, 20, p -> true)).isEqualTo(8080);
    }

    @Test
    void nextFreePortWhenTaken() {
        Set<Integer> taken = Set.of(8080, 8081);
        assertThat(DesktopPortCustomizer.firstFreePort(8080, 20, p -> !taken.contains(p))).isEqualTo(8082);
    }

    @Test
    void preferredPortWhenNothingIsFreeInTheRange() {
        assertThat(DesktopPortCustomizer.firstFreePort(8080, 3, p -> p > 8083)).isEqualTo(8080);
        assertThat(DesktopPortCustomizer.firstFreePort(65535, 20, p -> false)).isEqualTo(65535);
    }
}
