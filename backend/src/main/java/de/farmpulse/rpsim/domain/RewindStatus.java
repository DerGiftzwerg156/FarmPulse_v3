package de.farmpulse.rpsim.domain;

/** Handling state of a detected game-time rewind (savegame reloaded without saving, T-02). */
public enum RewindStatus {
    /** Waiting for an instructions_ack.json rebuilt by the mod from the reloaded savegame. */
    AWAITING_ACK,
    /** Rewind deeper than the auto threshold: the player decides. */
    AWAITING_PLAYER,
    /** Lost instructions were set back to PENDING. */
    RESENT,
    /** Player chose to keep the tool state without re-sending. */
    KEPT,
    /** Nothing was lost. */
    NOTHING_LOST
}
