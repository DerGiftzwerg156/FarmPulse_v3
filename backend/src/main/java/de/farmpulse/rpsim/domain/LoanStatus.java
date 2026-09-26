package de.farmpulse.rpsim.domain;

/** Loan lifecycle. */
public enum LoanStatus {
    ACTIVE,
    PAID_OFF,
    CALLED,
    /** T-03: the call-back could not be collected (insufficient funds); the bank collects as soon as possible. */
    DEFAULTED
}
