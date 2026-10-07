package de.farmpulse.rpsim.domain;

/** NarrationJob lifecycle. */
public enum NarrationJobStatus {
    PENDING,
    /** Technical review 10/2026, Phase 1.6: claimed by the worker, the AI call runs (until {@code leaseUntil}). */
    IN_PROGRESS,
    DONE,
    FALLBACK
}
