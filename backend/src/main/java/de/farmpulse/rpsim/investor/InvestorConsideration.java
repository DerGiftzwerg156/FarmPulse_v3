package de.farmpulse.rpsim.investor;

import java.util.EnumSet;
import java.util.Set;

/**
 * Roadmap V3.2 R32-I3: catalogue of the considerations - goods and milk (W1 total over the term with a minimum per
 * year, W2 per month, W3 milk per month), money (R1 profit share, R2 fixed payout), animals and obligations (A1
 * animals per year, A2 animal welfare, A3 crop obligation, A4 growth target) and rights (P1 veto on field sales, P2
 * right of first refusal, P3 holiday flat, P4 visit, P5 name in the village paper).
 */
public enum InvestorConsideration {
    W1, W2, W3, R1, R2, A1, A2, A3, A4, P1, P2, P3, P4, P5;

    /** Can be the main consideration (its quantity or rate takes the rest of the target value). */
    public static final Set<InvestorConsideration> MAIN = EnumSet.of(W1, W2, W3, A1, R1, R2);
    /** Side considerations with a fixed (or computed) value per year (owner decision 2026-10-08). */
    public static final Set<InvestorConsideration> SIDE = EnumSet.of(A2, A3, A4, P1, P2, P3, P4, P5);
    /** Delivered by the player with "Liefern". */
    public static final Set<InvestorConsideration> DELIVERED = EnumSet.of(W1, W2, W3, A1);
    /** Checked per month; the others per FS25 year (P1 on a sale, P3 / P5 never). */
    public static final Set<InvestorConsideration> MONTHLY = EnumSet.of(W2, W3, A2);

    public static InvestorConsideration of(String type) {
        return valueOf(type);
    }
}
