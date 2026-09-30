package de.farmpulse.rpsim.domain;

/** Entries of the repayment history. */
public enum LoanPaymentType {
    DISBURSEMENT,
    INSTALLMENT,
    MISSED,
    PENALTY,
    CALLBACK,
    DEFERRAL,
    /** Sondertilgung: early repayment by the player (full repayment includes the pro-rata interest). */
    SPECIAL_REPAYMENT,
    /** Fee of a Sondertilgung above the yearly free limit (Vorfälligkeitsentschädigung). */
    PREPAYMENT_FEE,
    /** T-03: booking the mod could not execute (e.g. insufficient funds) - reversed, no longer counts. */
    REVERSED
}
