package de.farmpulse.rpsim.village;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.config.RpsimProperties.Reputation;
import de.farmpulse.rpsim.village.VillageReputationService.Tier;
import org.junit.jupiter.api.Test;

class VillageReputationFormulaTest {

    final Reputation cfg = new RpsimProperties().getFormulas().getReputation();

    @Test
    void weightedAndClamped() {
        assertThat(VillageReputationService.score(50, 25, cfg)).isCloseTo(40, within(1e-9));
        assertThat(VillageReputationService.score(100, 1000, cfg)).isEqualTo(100);
        assertThat(VillageReputationService.score(-100, -1000, cfg)).isEqualTo(-100);
    }

    @Test
    void tierBoundaries() {
        assertThat(VillageReputationService.tier(25, cfg)).isEqualTo(Tier.GOOD);
        assertThat(VillageReputationService.tier(24.9, cfg)).isEqualTo(Tier.NEUTRAL);
        assertThat(VillageReputationService.tier(-24.9, cfg)).isEqualTo(Tier.NEUTRAL);
        assertThat(VillageReputationService.tier(-25, cfg)).isEqualTo(Tier.CONTROVERSIAL);
    }

    @Test
    void baseTrustForNewCharactersIsCapped() {
        assertThat(VillageReputationService.baseTrust(50, cfg)).isEqualTo(10);
        assertThat(VillageReputationService.baseTrust(100, cfg)).isEqualTo(15);
        assertThat(VillageReputationService.baseTrust(-100, cfg)).isEqualTo(-15);
        assertThat(VillageReputationService.baseTrust(75, cfg)).isEqualTo(15);
        assertThat(VillageReputationService.baseTrust(74, cfg)).isCloseTo(14.8, within(1e-9));
    }
}
