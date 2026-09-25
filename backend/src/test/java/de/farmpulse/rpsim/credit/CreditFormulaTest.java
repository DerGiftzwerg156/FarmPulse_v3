package de.farmpulse.rpsim.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.config.RpsimProperties.Credit;
import de.farmpulse.rpsim.domain.CreditDecision;
import de.farmpulse.rpsim.domain.CreditReasonCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pure formula tests incl. exact threshold boundaries and the trust-cap safety principle. */
class CreditFormulaTest {

    final Credit def = new RpsimProperties().getFormulas().getCredit();
    final Credit hard = new RpsimProperties().getFormulas().getCreditHard();

    static CreditFormula.Inputs inputs(double cashflow, double assets, double debt, double balance, double amount,
                                       double history, double trust) {
        return new CreditFormula.Inputs(cashflow, true, 0, 1000, assets, debt, balance, amount, history, trust);
    }

    @ParameterizedTest
    @CsvSource({"74.9,COUNTER_OFFER", "75.0,APPROVED", "75.1,APPROVED", "45.0,COUNTER_OFFER", "44.9,REJECTED",
            "45.1,COUNTER_OFFER", "0,REJECTED", "100,APPROVED"})
    void defaultThresholdsAreExact(double score, CreditDecision expected) {
        assertThat(CreditFormula.decide(score, def)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"84.9,COUNTER_OFFER", "85.0,APPROVED", "55.0,COUNTER_OFFER", "54.9,REJECTED", "75.0,COUNTER_OFFER"})
    void hardProfileThresholdsAreExact(double score, CreditDecision expected) {
        assertThat(CreditFormula.decide(score, hard)).isEqualTo(expected);
    }

    @Test
    void trustBonusIsCapped() {
        assertThat(CreditFormula.trustBonus(100, def)).isEqualTo(8);
        assertThat(CreditFormula.trustBonus(-100, def)).isEqualTo(-8);
        assertThat(CreditFormula.trustBonus(80, def)).isEqualTo(8);
        assertThat(CreditFormula.trustBonus(79, def)).isCloseTo(7.9, within(1e-9));
        assertThat(CreditFormula.trustBonus(50, def)).isEqualTo(5);
        assertThat(CreditFormula.trustBonus(100, hard)).isEqualTo(5);
        assertThat(CreditFormula.trustBonus(-100, hard)).isEqualTo(-5);
    }

    /**
     * Security principle: the trust range must stay smaller than the gap between the result thresholds, so trust can
     * tip borderline cases but never bridge a gross shortfall (never REJECTED -> APPROVED).
     */
    @Test
    void trustCapIsSmallerThanThresholdGap() {
        for (Credit cfg : new Credit[]{def, hard}) {
            assertThat(2 * cfg.getTrustCap()).isLessThan(cfg.getApproveThreshold() - cfg.getCounterThreshold());
            for (double core = 0; core <= 100; core += 0.1) {
                CreditDecision without = CreditFormula.decide(core, cfg);
                for (double trust = -100; trust <= 100; trust += 5) {
                    double fin = Math.max(0, Math.min(100, core + CreditFormula.trustBonus(trust, cfg)));
                    CreditDecision with = CreditFormula.decide(fin, cfg);
                    assertThat(Math.abs(with.ordinal() - without.ordinal())).as("core %.1f trust %.0f", core, trust)
                            .isLessThanOrEqualTo(1);
                    if (core < cfg.getCounterThreshold() - cfg.getTrustCap()) {
                        assertThat(with).isEqualTo(CreditDecision.REJECTED);
                    }
                }
            }
        }
    }

    @Test
    void trustTipsABorderlineCase() {
        // core slightly below 75: good relationship tips it to approval
        CreditFormula.Inputs base = inputs(1400, 1_000_000, 0, 10_000, 100_000, 70, 0);
        CreditFormula.Result neutral = CreditFormula.evaluate(base, def);
        assertThat(neutral.coreScore()).isBetween(67.0, 75.0);
        assertThat(neutral.decision()).isEqualTo(CreditDecision.COUNTER_OFFER);
        CreditFormula.Result friendly = CreditFormula.evaluate(inputs(1400, 1_000_000, 0, 10_000, 100_000, 70, 100), def);
        assertThat(friendly.finalScore()).isCloseTo(neutral.coreScore() + 8, within(1e-9));
        assertThat(friendly.decision()).isEqualTo(CreditDecision.APPROVED);
    }

    @Test
    void metricsSaturateInsteadOfGrowingForever() {
        CreditFormula.Components rich = CreditFormula.components(inputs(1e9, 1e12, 0, 1e12, 1000, 100, 0), def);
        assertThat(rich.debtServiceCoverage()).isEqualTo(100);
        assertThat(rich.equityRatio()).isCloseTo(100, within(1e-6));
        assertThat(rich.liquidityBuffer()).isEqualTo(100);
        assertThat(rich.loanToFarmSize()).isEqualTo(100);
        CreditFormula.Result r = CreditFormula.evaluate(inputs(1e9, 1e12, 0, 1e12, 1000, 100, 0), def);
        assertThat(r.coreScore()).isCloseTo(100, within(1e-6));
        assertThat(r.decision()).isEqualTo(CreditDecision.APPROVED);
        assertThat(r.reasonCategory()).isEqualTo(CreditReasonCategory.SOLID_FINANCES);
    }

    @Test
    void weightsCombineExactly() {
        // dsc 50, equity (200k-100k)/(200k+10k) pro forma, liquidity 100, loanToFarm 200k/110k/3, history 70
        CreditFormula.Inputs in = new CreditFormula.Inputs(1000, true, 0, 1000, 200_000, 100_000, 1_000_000, 10_000,
                70, 0);
        CreditFormula.Result r = CreditFormula.evaluate(in, def);
        assertThat(r.components().debtServiceCoverage()).isCloseTo(50, within(1e-9));
        double eq = 100_000.0 / 210_000 * 100;
        assertThat(r.components().equityRatio()).isCloseTo(eq, within(1e-9));
        double ltf = 200_000.0 / 110_000 / 3 * 100;
        assertThat(r.components().loanToFarmSize()).isCloseTo(ltf, within(1e-9));
        assertThat(r.coreScore()).isCloseTo(0.3 * 50 + 0.25 * eq + 0.15 * 100 + 0.15 * ltf + 0.15 * 70, within(1e-9));
    }

    @Test
    void existingDebtIncludingVanillaLoanReducesEquityAndFarmSize() {
        CreditFormula.Components noDebt = CreditFormula.components(inputs(0, 300_000, 0, 0, 100_000, 70, 0), def);
        CreditFormula.Components debt = CreditFormula.components(inputs(0, 300_000, 150_000, 0, 100_000, 70, 0), def);
        assertThat(debt.equityRatio()).isLessThan(noDebt.equityRatio());
        assertThat(debt.loanToFarmSize()).isLessThan(noDebt.loanToFarmSize());
    }

    @Test
    void absurdAmountIsRejectedEvenWithoutCashflowHistory() {
        var in = new CreditFormula.Inputs(0, false, 0, 1_000_000, 564_000, 80_000, 1_000, 50_000_000, 70, 100);
        CreditFormula.Result r = CreditFormula.evaluate(in, def);
        assertThat(r.decision()).isEqualTo(CreditDecision.REJECTED);
    }

    @Test
    void noCashflowHistoryUsesConfiguredNeutralValue() {
        var in = new CreditFormula.Inputs(0, false, 0, 1000, 1, 0, 0, 1, 70, 0);
        assertThat(CreditFormula.components(in, def).debtServiceCoverage()).isEqualTo(def.getDebtServiceCoverageNoHistory());
    }

    @Test
    void rejectionNamesWeakestMetricOnly() {
        CreditFormula.Result r = CreditFormula.evaluate(inputs(10_000, 50_000, 45_000, 100_000, 5_000, 70, 0), def);
        assertThat(r.reasonCategory()).isEqualTo(CreditReasonCategory.INSUFFICIENT_EQUITY);
        CreditFormula.Result r2 = CreditFormula.evaluate(inputs(-5_000, 1_000_000, 0, 1_000_000, 5_000, 70, 0), def);
        assertThat(r2.reasonCategory()).isEqualTo(CreditReasonCategory.INSUFFICIENT_CASHFLOW);
    }

    @Test
    void counterTermsScaleWithGap() {
        CreditFormula.Terms nearApproval = CreditFormula.counterTerms(74.99, 100_000, 60, def);
        CreditFormula.Terms atCounter = CreditFormula.counterTerms(45, 100_000, 60, def);
        assertThat(nearApproval.amount()).isEqualTo(100_000);
        assertThat(nearApproval.interestRate()).isCloseTo(0.05, within(1e-4));
        assertThat(atCounter.amount()).isEqualTo(50_000);
        assertThat(atCounter.interestRate()).isCloseTo(0.09, within(1e-9));
        assertThat(atCounter.termMonths()).isEqualTo(48);
    }

    @Test
    void annuityInstallment() {
        assertThat(CreditFormula.monthlyInstallment(12_000, 0, 12)).isEqualTo(1000);
        long rate = CreditFormula.monthlyInstallment(100_000, 0.06, 60);
        assertThat(rate).isBetween(1933L, 1934L);
    }

    @Test
    void paymentHistoryStartsNeutralAndIsBounded() {
        assertThat(CreditFormula.paymentHistoryScore(0, 0, def)).isEqualTo(70);
        assertThat(CreditFormula.paymentHistoryScore(100, 0, def)).isEqualTo(100);
        assertThat(CreditFormula.paymentHistoryScore(0, 10, def)).isEqualTo(0);
        assertThat(CreditFormula.paymentHistoryScore(0, 1, def)).isEqualTo(55);
    }

    /** AP-9.1: degenerate inputs never divide by zero and land on the documented neutral/extreme values. */
    @Test
    void degenerateInputsAreHandled() {
        // no installments at all -> full coverage; no assets -> equity 0; nothing requested -> liquidity 100
        var c = CreditFormula.components(new CreditFormula.Inputs(1000, true, 0, 0, 0, 0, 0, 0, 70, 0), def);
        assertThat(c.debtServiceCoverage()).isEqualTo(100);
        assertThat(c.equityRatio()).isZero();
        assertThat(c.liquidityBuffer()).isEqualTo(100);
        assertThat(c.loanToFarmSize()).isEqualTo(100);
        // history is clamped into 0..100
        assertThat(CreditFormula.components(new CreditFormula.Inputs(0, false, 0, 0, 0, 0, 0, 0, 180, 0), def).paymentHistory())
                .isEqualTo(100);
    }

    @Test
    void annuityEdgeCases() {
        assertThat(CreditFormula.monthlyInstallment(12_000, 0.05, 0)).isEqualTo(12_000);
        assertThat(CreditFormula.monthlyInstallment(12_000, 0.0, 12)).isEqualTo(1_000);
    }
}
