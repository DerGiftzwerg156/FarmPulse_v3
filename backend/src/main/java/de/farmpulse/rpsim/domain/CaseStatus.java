package de.farmpulse.rpsim.domain;

/** Lifecycle of a service case. */
public enum CaseStatus {
    /** Waiting for a decision of the player (e.g. compensation offer). */
    AWAITING_PLAYER,
    /** Accepted by the player, waiting for the game state (e.g. animals sold in the game). */
    IN_PROGRESS,
    /** Closed: money booked / advice given. */
    SETTLED,
    DECLINED,
    EXPIRED
}
