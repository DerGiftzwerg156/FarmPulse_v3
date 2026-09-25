package de.farmpulse.rpsim.domain;

/** Entries of the repayment history. */
public enum LoanPaymentType {
    DISBURSEMENT,
    INSTALLMENT,
    MISSED,
    PENALTY,
    CALLBACK,
    DEFERRAL
}
