package de.farmpulse.rpsim.domain;

/** Roadmap V2 R2-F: state of a question asked in the game. */
public enum PromptStatus {
    /** Sent to the mod, no answer yet. */
    OPEN,
    /** Answered in the game (the answer was carried out or failed, see result). */
    ANSWERED,
    /** Decided in the browser meanwhile or the occasion ended (owner decision: the mod drops it). */
    WITHDRAWN,
    /** Not answered before it expired. */
    EXPIRED,
    /** The mod cannot show questions (older version): the decision stays in the browser. */
    NOT_SHOWN
}
