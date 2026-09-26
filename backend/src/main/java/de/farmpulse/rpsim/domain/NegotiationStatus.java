package de.farmpulse.rpsim.domain;

/** Negotiation lifecycle. */
public enum NegotiationStatus {
    OPEN,
    ACCEPTED,
    REJECTED,
    WITHDRAWN,
    LOST,
    EXPIRED,
    /** T-03: agreed, but the mod could not execute the deal (e.g. insufficient funds) - nothing changed hands. */
    FAILED
}
