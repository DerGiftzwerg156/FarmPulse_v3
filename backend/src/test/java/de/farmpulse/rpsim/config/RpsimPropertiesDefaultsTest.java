package de.farmpulse.rpsim.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** application.yml and the Java defaults must describe the same placeholder values. */
@SpringBootTest
class RpsimPropertiesDefaultsTest {

    @Autowired
    RpsimProperties bound;

    @Test
    void formulaDefaultsInYamlMatchJavaDefaults() {
        assertThat(bound.getFormulas()).usingRecursiveComparison().isEqualTo(new RpsimProperties().getFormulas());
    }

    @Test
    void hardCreditProfileIsStricter() {
        var f = bound.getFormulas();
        assertThat(f.getCreditHard().getApproveThreshold()).isEqualTo(85);
        assertThat(f.getCreditHard().getCounterThreshold()).isEqualTo(55);
        assertThat(f.getCreditHard().getTrustCap()).isEqualTo(5);
        assertThat(f.getCredit().getApproveThreshold()).isEqualTo(75);
    }
}
