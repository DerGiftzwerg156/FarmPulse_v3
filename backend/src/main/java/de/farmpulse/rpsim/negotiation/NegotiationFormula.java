package de.farmpulse.rpsim.negotiation;

import static de.farmpulse.rpsim.common.Formulas.clamp;

import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationTrait;
import de.farmpulse.rpsim.domain.OfferResult;

/**
 * Pure price finding of the negotiation engine (technical concept "Verhandlungs-Preisfindung"):
 * <pre>
 * minAccept          = basePrice · (1 − stubbornnessDiscount), stubbornnessDiscount ∈ [0.05, 0.20] from trait
 * trustAdjustment    = clamp(trustScore / 20, −0.05, +0.05)
 * effectiveMinAccept = minAccept · (1 − trustAdjustment)
 * offer ≥ effectiveMinAccept        → ACCEPTED
 * offer ≥ 0.9 · effectiveMinAccept  → COUNTER (= effectiveMinAccept)
 * otherwise                          → REJECTED (final after round 3)
 * </pre>
 * Same safety principle as the credit score: the trust influence is capped so it can never distort the base price
 * arbitrarily.
 */
public final class NegotiationFormula {

    private NegotiationFormula() {
    }

    public static double stubbornnessDiscount(NegotiationTrait trait, Negotiation cfg) {
        return clamp(cfg.getStubbornnessDiscount().getOrDefault(trait.name(), 0.12), 0.05, 0.20);
    }

    public static double trustAdjustment(double trustScore, Negotiation cfg) {
        return clamp(trustScore / cfg.getTrustDivisor(), -cfg.getTrustCap(), cfg.getTrustCap());
    }

    public static double minAccept(long basePrice, NegotiationTrait trait, Negotiation cfg) {
        return basePrice * (1 - stubbornnessDiscount(trait, cfg));
    }

    public static long effectiveMinAccept(long basePrice, NegotiationTrait trait, double trustScore, Negotiation cfg) {
        return Math.round(minAccept(basePrice, trait, cfg) * (1 - trustAdjustment(trustScore, cfg)));
    }

    /** Player buys: evaluates the offer against the (hidden) effective minimum of the seller. */
    public static OfferResult evaluatePurchase(long offer, long effectiveMinAccept, Negotiation cfg) {
        if (offer >= effectiveMinAccept) {
            return OfferResult.ACCEPTED;
        }
        if (offer >= cfg.getCounterBand() * effectiveMinAccept) {
            return OfferResult.COUNTER;
        }
        return OfferResult.REJECTED;
    }

    /**
     * Player sells (roles swapped): the NPC buyer accepts up to basePrice · (1 + discount) · (1 + trustAdjustment);
     * a demand within 1/0.9 of that limit yields a counter offer at the limit.
     */
    public static long effectiveMaxAccept(long basePrice, NegotiationTrait trait, double trustScore, Negotiation cfg) {
        return Math.round(basePrice * (1 + stubbornnessDiscount(trait, cfg)) * (1 + trustAdjustment(trustScore, cfg)));
    }

    public static OfferResult evaluateSale(long demand, long effectiveMaxAccept, Negotiation cfg) {
        if (demand <= effectiveMaxAccept) {
            return OfferResult.ACCEPTED;
        }
        if (demand * cfg.getCounterBand() <= effectiveMaxAccept) {
            return OfferResult.COUNTER;
        }
        return OfferResult.REJECTED;
    }

    /** Auction: npcMaxBid = basePrice · random(0.9, 1.15) from a character seed (no AI call). */
    public static long npcMaxBid(long basePrice, long seed, Negotiation cfg) {
        return Math.round(basePrice * RandomSource.seeded(seed).uniform(cfg.getNpcBidMin(), cfg.getNpcBidMax()));
    }

    /** Sale of own field: npcCounterOffer = askingPrice · random(0.85, 1.0), capped at the buyer's limit. */
    public static long npcCounterOffer(long askingPrice, long effectiveMaxAccept, long seed, Negotiation cfg) {
        long raw = Math.round(askingPrice * RandomSource.seeded(seed).uniform(cfg.getNpcCounterOfferMin(),
                cfg.getNpcCounterOfferMax()));
        return Math.min(raw, effectiveMaxAccept);
    }

    /** Visible leading NPC bid after a player bid below the top NPC limit. */
    public static long leadingBid(long playerBid, long topNpcMax, long basePrice, Negotiation cfg) {
        return Math.min(topNpcMax, playerBid + Math.round(basePrice * cfg.getAuctionBidIncrementRatio()));
    }
}
