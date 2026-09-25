package de.farmpulse.rpsim.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * AP-9.1: debtServiceCoverage uses the cash-flow trend of the FactsSnapshot series (moving window), not a value
 * pre-computed by the mod. Game month = rpsim.time.game-days-per-month (1 in the default config).
 */
@SpringBootTest
@Transactional
@Import(Fixtures.class)
class CreditScoringServiceTest {

    @Autowired Fixtures fx;
    @Autowired CreditScoringService scoring;

    Savegame sg;
    long now;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        now = GameTime.days(10);
        sg.setCurrentGameTime(now);
    }

    private void snapshotAt(long gameTime, long balance) {
        fx.snapshot(sg, gameTime, balance, TestData.farmFacts(sg.getBridgeSavegameId(), gameTime, balance));
    }

    @Test
    void cashflowTrendFromTheSnapshotWindow() {
        snapshotAt(now - GameTime.days(2), 100_000);
        snapshotAt(now - GameTime.days(1), 104_000);
        snapshotAt(now, 110_000);
        CreditFormula.Inputs in = scoring.inputs(sg, 10_000, 12, 0.05);
        assertThat(in.hasCashflowHistory()).isTrue();
        // +10,000 over 2 game days = 2 game months -> 5,000 per month (first/last of the window)
        assertThat(in.monthlyOperatingCashflow()).isCloseTo(5_000, within(1e-6));
        assertThat(in.balance()).isEqualTo(110_000);
    }

    @Test
    void historyShorterThanTheMinimumCountsAsNoHistory() {
        snapshotAt(now - GameTime.hours(12), 100_000);
        snapshotAt(now, 150_000);
        CreditFormula.Inputs in = scoring.inputs(sg, 10_000, 12, 0.05);
        assertThat(in.hasCashflowHistory()).isFalse();
        assertThat(in.monthlyOperatingCashflow()).isZero();
    }

    @Test
    void exactlyTheMinimumHistoryCounts() {
        snapshotAt(now - GameTime.days(1), 100_000);
        snapshotAt(now, 101_000);
        assertThat(scoring.inputs(sg, 10_000, 12, 0.05).hasCashflowHistory()).isTrue();
    }

    @Test
    void snapshotsOutsideTheWindowAreIgnored() {
        snapshotAt(now - GameTime.days(40), 0); // outside the 30-day window
        snapshotAt(now - GameTime.days(2), 100_000);
        snapshotAt(now, 100_000);
        assertThat(scoring.inputs(sg, 10_000, 12, 0.05).monthlyOperatingCashflow()).isZero();
    }

    @Test
    void withoutAnySnapshotEverythingIsZeroAndNeutral() {
        CreditFormula.Inputs in = scoring.inputs(sg, 10_000, 12, 0.05);
        assertThat(in.hasCashflowHistory()).isFalse();
        assertThat(in.totalAssets()).isZero();
        assertThat(in.balance()).isZero();
    }
}
