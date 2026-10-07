package de.farmpulse.rpsim.domain;

/** Technical review 10/2026, Phase 1.3: state of one listener for one queued event. */
public enum CycleStepStatus {
    /** Ran successfully (written in the listener's own transaction). */
    DONE,
    /** Failed; retried by the next cycle. */
    FAILED,
    /** Failed {@code rpsim.bridge.step-max-attempts} times; given up and reported as a notice. */
    SKIPPED
}
