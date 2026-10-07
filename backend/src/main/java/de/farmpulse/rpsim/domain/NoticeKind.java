package de.farmpulse.rpsim.domain;

/** Kinds of dashboard notices raised by the fact layer (texts are rendered by the frontend i18n). */
public enum NoticeKind {
    /** Deep game-time rewind: the player decides whether lost bookings are re-sent (TODO T-02). */
    REWIND_DECISION,
    /** Lost bookings were re-sent automatically after a short rewind (T-02). */
    REWIND_RESENT,
    /** The mod did not execute an instruction (FAILED / REJECTED ack, T-03). */
    INSTRUCTION_FAILED,
    /** Technical review 10/2026, Phase 1.3: a listener of the bridge cycle failed repeatedly and was skipped. */
    CYCLE_STEP_SKIPPED
}
