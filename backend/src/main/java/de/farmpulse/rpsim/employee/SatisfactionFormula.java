package de.farmpulse.rpsim.employee;

import static de.farmpulse.rpsim.common.Formulas.clamp;

import de.farmpulse.rpsim.config.RpsimProperties.Satisfaction;

/**
 * Technical concept "Satisfaction-Formel":
 * <pre>
 * satisfactionScore    = 0.25 · (payFairness + workload + appreciation + workingConditions)
 * effectMultiplier     = clamp(satisfactionScore / 100, 0.5, 1.2)
 * employeeEffectAmount = baselineOutputValue · (effectMultiplier − 1.0)
 * </pre>
 * The skill malus/bonus is an internal tool value (no FS25 worker parameter is touched).
 */
public final class SatisfactionFormula {

    private SatisfactionFormula() {
    }

    public static double score(double payFairness, double workload, double appreciation, double workingConditions,
                               Satisfaction cfg) {
        return cfg.getCategoryWeight() * (payFairness + workload + appreciation + workingConditions);
    }

    public static double effectMultiplier(double score, Satisfaction cfg) {
        return clamp(score / 100.0, cfg.getMultiplierMin(), cfg.getMultiplierMax());
    }

    public static long effectAmount(double multiplier, Satisfaction cfg) {
        return Math.round(cfg.getBaselineOutputValue() * (multiplier - 1.0));
    }

    /** Effective (tool-internal) skill after the satisfaction malus/bonus. */
    public static double effectiveSkill(int skill, double multiplier) {
        return clamp(skill * multiplier, 0, 100);
    }

    public static double decay(double value, double perDay, double days, Satisfaction cfg) {
        return clamp(value - perDay * Math.max(0, days), 0, cfg.getCategoryMax());
    }
}
