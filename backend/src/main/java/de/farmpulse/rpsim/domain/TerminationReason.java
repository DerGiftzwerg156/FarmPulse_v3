package de.farmpulse.rpsim.domain;

/** Reason stored when a character is TERMINATED. */
public enum TerminationReason {
    MOVED_AWAY,
    RETIREMENT,
    RESIGNED,
    DISMISSED,
    SUBSTITUTE_ENDED,
    REJECTED_APPLICANT,
    DRAFT_DISCARDED,
    /** Roadmap V3.1 R31-A5: the fixed-term contract of a seasonal worker ended. */
    CONTRACT_ENDED
}
