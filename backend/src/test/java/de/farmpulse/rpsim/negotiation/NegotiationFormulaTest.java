package de.farmpulse.rpsim.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.config.RpsimProperties.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationTrait;
import de.farmpulse.rpsim.domain.OfferResult;
import org.junit.jupiter.api.Test;

class NegotiationFormulaTest {

    final Negotiation cfg = new RpsimProperties().getFormulas().getNegotiation();

    @Test
    void minAcceptFromTrait() {
        assertThat(NegotiationFormula.minAccept(100_000, NegotiationTrait.NEGOTIABLE, cfg)).isCloseTo(80_000, within(1e-6));
        assertThat(NegotiationFormula.minAccept(100_000, NegotiationTrait.NEUTRAL, cfg)).isCloseTo(88_000, within(1e-6));
        assertThat(NegotiationFormula.minAccept(100_000, NegotiationTrait.STUBBORN, cfg)).isCloseTo(95_000, within(1e-6));
    }

    @Test
    void stubbornnessDiscountStaysWithinConceptRange() {
        cfg.getStubbornnessDiscount().put("NEGOTIABLE", 0.9);
        assertThat(NegotiationFormula.stubbornnessDiscount(NegotiationTrait.NEGOTIABLE, cfg)).isEqualTo(0.20);
        cfg.getStubbornnessDiscount().put("STUBBORN", 0.0);
        assertThat(NegotiationFormula.stubbornnessDiscount(NegotiationTrait.STUBBORN, cfg)).isEqualTo(0.05);
    }

    @Test
    void trustAdjustmentIsCapped() {
        assertThat(NegotiationFormula.trustAdjustment(100, cfg)).isEqualTo(0.05);
        assertThat(NegotiationFormula.trustAdjustment(-100, cfg)).isEqualTo(-0.05);
        assertThat(NegotiationFormula.trustAdjustment(0.5, cfg)).isCloseTo(0.025, within(1e-12));
        assertThat(NegotiationFormula.trustAdjustment(0, cfg)).isEqualTo(0);
    }

    /** Safety principle: trust never distorts the base price arbitrarily (max ±5 % around minAccept). */
    @Test
    void trustNeverDistortsBasePriceBeyondCap() {
        long base = 100_000;
        for (NegotiationTrait t : NegotiationTrait.values()) {
            double min = NegotiationFormula.minAccept(base, t, cfg);
            for (double trust = -100; trust <= 100; trust += 1) {
                long eff = NegotiationFormula.effectiveMinAccept(base, t, trust, cfg);
                assertThat(eff).isBetween(Math.round(min * 0.95) - 1, Math.round(min * 1.05) + 1);
                assertThat(eff).isGreaterThanOrEqualTo(Math.round(base * 0.80 * 0.95) - 1);
                long max = NegotiationFormula.effectiveMaxAccept(base, t, trust, cfg);
                assertThat(max).isLessThanOrEqualTo(Math.round(base * 1.20 * 1.05) + 1);
            }
        }
    }

    @Test
    void purchaseThresholdsAreExact() {
        long eff = 90_000;
        assertThat(NegotiationFormula.evaluatePurchase(90_000, eff, cfg)).isEqualTo(OfferResult.ACCEPTED);
        assertThat(NegotiationFormula.evaluatePurchase(90_001, eff, cfg)).isEqualTo(OfferResult.ACCEPTED);
        assertThat(NegotiationFormula.evaluatePurchase(89_999, eff, cfg)).isEqualTo(OfferResult.COUNTER);
        assertThat(NegotiationFormula.evaluatePurchase(81_000, eff, cfg)).isEqualTo(OfferResult.COUNTER);
        assertThat(NegotiationFormula.evaluatePurchase(80_999, eff, cfg)).isEqualTo(OfferResult.REJECTED);
    }

    @Test
    void saleThresholdsMirrorPurchase() {
        long effMax = 90_000;
        assertThat(NegotiationFormula.evaluateSale(90_000, effMax, cfg)).isEqualTo(OfferResult.ACCEPTED);
        assertThat(NegotiationFormula.evaluateSale(100_000, effMax, cfg)).isEqualTo(OfferResult.COUNTER);
        assertThat(NegotiationFormula.evaluateSale(100_001, effMax, cfg)).isEqualTo(OfferResult.REJECTED);
    }

    @Test
    void npcMaxBidIsDeterministicAndWithinBand() {
        for (long seed = 0; seed < 500; seed++) {
            long bid = NegotiationFormula.npcMaxBid(100_000, seed, cfg);
            assertThat(bid).isBetween(90_000L, 115_000L);
            assertThat(NegotiationFormula.npcMaxBid(100_000, seed, cfg)).isEqualTo(bid);
        }
    }

    @Test
    void npcCounterOfferWithinBandAndCappedByBuyerLimit() {
        for (long seed = 0; seed < 500; seed++) {
            long o = NegotiationFormula.npcCounterOffer(100_000, 1_000_000, seed, cfg);
            assertThat(o).isBetween(85_000L, 100_000L);
        }
        // an absurd asking price never produces an absurd first offer
        assertThat(NegotiationFormula.npcCounterOffer(10_000_000, 120_000, 1, cfg)).isEqualTo(120_000);
    }

    @Test
    void leadingBidUsesIncrementButNeverExceedsNpcLimit() {
        assertThat(NegotiationFormula.leadingBid(90_000, 110_000, 100_000, cfg)).isEqualTo(91_000);
        assertThat(NegotiationFormula.leadingBid(109_500, 110_000, 100_000, cfg)).isEqualTo(110_000);
    }
}
