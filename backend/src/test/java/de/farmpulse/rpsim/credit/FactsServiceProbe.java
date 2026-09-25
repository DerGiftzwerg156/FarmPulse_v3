package de.farmpulse.rpsim.credit;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TonePreset;
import org.springframework.stereotype.Component;

/** Verifies that the resolved credit profile follows the tone preset of the savegame. */
@Component
class FactsServiceProbe {

    private final CreditConfigResolver resolver;

    FactsServiceProbe(CreditConfigResolver resolver) {
        this.resolver = resolver;
    }

    void assertStricter(Savegame sg) {
        double expected = sg.getTonePreset() == TonePreset.HARSH ? 85 : 75;
        assertThat(resolver.forSavegame(sg).getApproveThreshold()).isEqualTo(expected);
    }
}
