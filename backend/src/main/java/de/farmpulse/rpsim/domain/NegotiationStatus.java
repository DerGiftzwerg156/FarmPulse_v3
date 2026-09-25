package de.farmpulse.rpsim.domain;

/** Negotiation lifecycle. */
public enum NegotiationStatus {
    OPEN,
    ACCEPTED,
    REJECTED,
    WITHDRAWN,
    LOST,
    EXPIRED
}
