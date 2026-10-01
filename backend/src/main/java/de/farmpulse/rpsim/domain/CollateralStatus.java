package de.farmpulse.rpsim.domain;

/** Roadmap V3 R3-K1: lifecycle of a field offered or pledged as loan collateral. */
public enum CollateralStatus {
    /** Chosen by the player in a credit application that is not decided yet. */
    REQUESTED,
    /** Named by the bank in a counter offer "mit Grundschuld" (pledged when the player accepts it). */
    PROPOSED,
    /** Grundschuld of a running loan. */
    PLEDGED,
    /** Free again (loan repaid, application closed, field sold). */
    RELEASED,
    /** Taken by the bank on a call-back (harsh world mode). */
    REALISED
}
