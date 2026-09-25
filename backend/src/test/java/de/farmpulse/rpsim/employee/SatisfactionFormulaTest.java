package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.config.RpsimProperties.Satisfaction;
import org.junit.jupiter.api.Test;

class SatisfactionFormulaTest {

    final Satisfaction cfg = new RpsimProperties().getFormulas().getSatisfaction();

    @Test
    void scoreIsQuarterOfTheFourCategories() {
        assertThat(SatisfactionFormula.score(80, 60, 40, 20, cfg)).isCloseTo(50, within(1e-9));
    }

    @Test
    void effectMultiplierClampBoundaries() {
        assertThat(SatisfactionFormula.effectMultiplier(10, cfg)).isEqualTo(0.5);
        assertThat(SatisfactionFormula.effectMultiplier(49.9, cfg)).isEqualTo(0.5);
        assertThat(SatisfactionFormula.effectMultiplier(50, cfg)).isEqualTo(0.5);
        assertThat(SatisfactionFormula.effectMultiplier(50.1, cfg)).isCloseTo(0.501, within(1e-9));
        assertThat(SatisfactionFormula.effectMultiplier(100, cfg)).isEqualTo(1.0);
        assertThat(SatisfactionFormula.effectMultiplier(119.9, cfg)).isCloseTo(1.199, within(1e-9));
        assertThat(SatisfactionFormula.effectMultiplier(120, cfg)).isEqualTo(1.2);
        assertThat(SatisfactionFormula.effectMultiplier(500, cfg)).isEqualTo(1.2);
    }

    @Test
    void effectAmountIsBaselineTimesDeviation() {
        assertThat(SatisfactionFormula.effectAmount(0.5, cfg)).isEqualTo(-750);
        assertThat(SatisfactionFormula.effectAmount(1.0, cfg)).isZero();
        assertThat(SatisfactionFormula.effectAmount(1.2, cfg)).isEqualTo(300);
    }

    @Test
    void bonusOnlyReachableWhenCategoriesMayExceedHundred() {
        double maxScore = SatisfactionFormula.score(100, 100, 100, 100, cfg);
        assertThat(SatisfactionFormula.effectMultiplier(maxScore, cfg)).isEqualTo(1.0);
        Satisfaction wide = new RpsimProperties().getFormulas().getSatisfaction();
        wide.setCategoryMax(120);
        assertThat(SatisfactionFormula.effectMultiplier(SatisfactionFormula.score(120, 120, 120, 120, wide), wide))
                .isEqualTo(1.2);
    }

    @Test
    void decayNeverBelowZero() {
        assertThat(SatisfactionFormula.decay(70, 1, 10, cfg)).isEqualTo(60);
        assertThat(SatisfactionFormula.decay(5, 1, 10, cfg)).isEqualTo(0);
        assertThat(SatisfactionFormula.decay(50, 1, -3, cfg)).isEqualTo(50);
    }

    @Test
    void effectiveSkillIsToolInternalMalus() {
        assertThat(SatisfactionFormula.effectiveSkill(80, 0.5)).isEqualTo(40);
        assertThat(SatisfactionFormula.effectiveSkill(90, 1.2)).isEqualTo(100);
    }
}
