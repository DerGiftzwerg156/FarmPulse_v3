package de.farmpulse.rpsim.credit;

import static de.farmpulse.rpsim.common.Formulas.clamp;
import static de.farmpulse.rpsim.common.Formulas.saturate;

import java.util.Comparator;
import java.util.Map;

import de.farmpulse.rpsim.config.RpsimProperties.Credit;
import de.farmpulse.rpsim.domain.CreditDecision;
import de.farmpulse.rpsim.domain.CreditReasonCategory;

/**
 * Pure credit scoring formula (technical concept "Bonitäts-Score"). No I/O, fully unit-testable:
 * <pre>
 * coreScore  = 0.30·debtServiceCoverage + 0.25·equityRatio + 0.15·liquidityBuffer
 *            + 0.15·loanToFarmSize + 0.15·paymentHistoryScore
 * trustBonus = clamp(trustScore / 10, -8, +8)
 * finalScore = clamp(coreScore + trustBonus, 0, 100)
 * ≥ 75 approved | 45–75 counter offer | < 45 rejected
 * </pre>
 * Every weight/threshold comes from {@link Credit} (configuration, profile HARSH = stricter bank).
 */
public final class CreditFormula {

    private CreditFormula() {
    }

    /** Raw inputs (all taken from live snapshot data + loan history). */
    public record Inputs(double monthlyOperatingCashflow, boolean hasCashflowHistory, double existingMonthlyInstallments,
                         double newMonthlyInstallment, double totalAssets, double totalDebt, double balance,
                         double requestedAmount, double paymentHistoryScore, double trustScore) {
    }

    /** The five normalised metrics (0..100 each). */
    public record Components(double debtServiceCoverage, double equityRatio, double liquidityBuffer, double loanToFarmSize,
                             double paymentHistory) {
    }

    public record Result(Components components, double coreScore, double trustBonus, double finalScore,
                         CreditDecision decision, CreditReasonCategory reasonCategory) {
    }

    public static Components components(Inputs in, Credit cfg) {
        double dsc;
        if (!in.hasCashflowHistory()) {
            dsc = cfg.getDebtServiceCoverageNoHistory();
        } else {
            double service = in.existingMonthlyInstallments() + in.newMonthlyInstallment();
            double coverage = service <= 0 ? cfg.getDebtServiceCoverageFull() : in.monthlyOperatingCashflow() / service;
            dsc = saturate(coverage, cfg.getDebtServiceCoverageFull());
        }
        // Equity / assets, pro forma after financing (the loan proceeds add the same amount to assets and debt):
        // (assets - debt) / (assets + requested). Existing liabilities incl. the vanilla loan reduce equity.
        double proFormaAssets = in.totalAssets() + Math.max(0, in.requestedAmount());
        double equityRatio = proFormaAssets <= 0 ? 0
                : clamp((in.totalAssets() - in.totalDebt()) / proFormaAssets * 100.0, 0, 100);
        double liquidity = in.requestedAmount() <= 0 ? 100 : saturate(in.balance() / in.requestedAmount(), cfg.getLiquidityFullRatio());
        // Farm size relative to total debt incl. the requested amount; existing debt reduces it too.
        double exposure = in.totalDebt() + in.requestedAmount();
        double loanToFarmSize = exposure <= 0 ? 100 : saturate(in.totalAssets() / exposure, cfg.getLoanToFarmSizeFullRatio());
        double history = clamp(in.paymentHistoryScore(), 0, 100);
        return new Components(dsc, equityRatio, liquidity, loanToFarmSize, history);
    }

    public static double trustBonus(double trustScore, Credit cfg) {
        return clamp(trustScore / cfg.getTrustDivisor(), -cfg.getTrustCap(), cfg.getTrustCap());
    }

    public static Result evaluate(Inputs in, Credit cfg) {
        Components c = components(in, cfg);
        double core = cfg.getWeightDebtServiceCoverage() * c.debtServiceCoverage()
                + cfg.getWeightEquityRatio() * c.equityRatio()
                + cfg.getWeightLiquidityBuffer() * c.liquidityBuffer()
                + cfg.getWeightLoanToFarmSize() * c.loanToFarmSize()
                + cfg.getWeightPaymentHistory() * c.paymentHistory();
        double bonus = trustBonus(in.trustScore(), cfg);
        double fin = clamp(core + bonus, 0, 100);
        CreditDecision decision = decide(fin, cfg);
        CreditReasonCategory reason = decision == CreditDecision.APPROVED ? CreditReasonCategory.SOLID_FINANCES : weakest(c);
        return new Result(c, core, bonus, fin, decision, reason);
    }

    public static CreditDecision decide(double finalScore, Credit cfg) {
        if (finalScore >= cfg.getApproveThreshold()) {
            return CreditDecision.APPROVED;
        }
        if (finalScore >= cfg.getCounterThreshold()) {
            return CreditDecision.COUNTER_OFFER;
        }
        return CreditDecision.REJECTED;
    }

    /** Coarse category of the weakest metric - the only thing the narration ever learns about the score. */
    static CreditReasonCategory weakest(Components c) {
        return Map.of(
                        CreditReasonCategory.INSUFFICIENT_CASHFLOW, c.debtServiceCoverage(),
                        CreditReasonCategory.INSUFFICIENT_EQUITY, c.equityRatio(),
                        CreditReasonCategory.INSUFFICIENT_LIQUIDITY, c.liquidityBuffer(),
                        CreditReasonCategory.LOAN_TOO_LARGE_FOR_FARM, c.loanToFarmSize(),
                        CreditReasonCategory.POOR_PAYMENT_HISTORY, c.paymentHistory())
                .entrySet().stream()
                .min(Comparator.<Map.Entry<CreditReasonCategory, Double>>comparingDouble(Map.Entry::getValue)
                        .thenComparing(e -> e.getKey().ordinal()))
                .map(Map.Entry::getKey).orElseThrow();
    }

    /** Counter-offer terms scaled by the gap to the approval threshold ("Konditionen skaliert nach Lücke bis 75"). */
    public record Terms(long amount, int termMonths, double interestRate) {
    }

    public static Terms counterTerms(double finalScore, long amount, int termMonths, Credit cfg) {
        double gap = clamp((cfg.getApproveThreshold() - finalScore) / (cfg.getApproveThreshold() - cfg.getCounterThreshold()), 0, 1);
        long offered = Math.max(100, Math.round(amount * (1 - cfg.getCounterMaxAmountReduction() * gap) / 100.0) * 100);
        int term = Math.max(cfg.getMinTermMonths(), termMonths - (int) Math.round(cfg.getCounterMaxTermReductionMonths() * gap));
        double rate = cfg.getBaseInterestRate() + cfg.getCounterMaxInterestSurcharge() * gap;
        return new Terms(offered, term, rate);
    }

    /** Annuity installment per game month (annual rate / 12). */
    public static long monthlyInstallment(long principal, double annualRate, int termMonths) {
        if (termMonths <= 0) {
            return principal;
        }
        double r = annualRate / 12.0;
        if (r <= 0) {
            return (long) Math.ceil(principal / (double) termMonths);
        }
        return (long) Math.ceil(principal * r / (1 - Math.pow(1 + r, -termMonths)));
    }

    /** Payment history: neutral start, + per on-time installment, - per missed installment. */
    public static double paymentHistoryScore(long onTime, long missed, Credit cfg) {
        return clamp(cfg.getPaymentHistoryNeutral() + onTime * cfg.getPaymentHistoryOnTimeGain()
                - missed * cfg.getPaymentHistoryMissedPenalty(), 0, 100);
    }
}
