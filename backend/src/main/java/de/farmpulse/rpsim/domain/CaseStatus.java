package de.farmpulse.rpsim.domain;

/** Lifecycle of a service case. */
public enum CaseStatus {
    /** Waiting for a decision of the player (e.g. compensation offer). */
    AWAITING_PLAYER,
    /** Closed: money booked / advice given. */
    SETTLED,
    DECLINED,
    EXPIRED
}
