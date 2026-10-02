package de.farmpulse.rpsim.domain;

/** Auction (system), direct negotiation (player) or sale of own field. */
public enum NegotiationKind {
    AUCTION,
    DIRECT,
    SALE_OFFER,
    /** Roadmap V3 R3-L1: lease-out of an own field - the amount is the rent per ha and month. */
    LEASE_OFFER
}
