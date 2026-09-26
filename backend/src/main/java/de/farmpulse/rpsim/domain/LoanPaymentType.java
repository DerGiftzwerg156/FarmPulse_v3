package de.farmpulse.rpsim.domain;

/** Entries of the repayment history. */
public enum LoanPaymentType {
    DISBURSEMENT,
    INSTALLMENT,
    MISSED,
    PENALTY,
    CALLBACK,
    DEFERRAL,
    /** T-03: booking the mod could not execute (e.g. insufficient funds) - reversed, no longer counts. */
    REVERSED
}
