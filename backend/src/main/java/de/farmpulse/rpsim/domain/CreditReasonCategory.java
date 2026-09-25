package de.farmpulse.rpsim.domain;

/** Coarse reason categories - never the raw score. */
public enum CreditReasonCategory {
    SOLID_FINANCES,
    INSUFFICIENT_EQUITY,
    INSUFFICIENT_CASHFLOW,
    INSUFFICIENT_LIQUIDITY,
    LOAN_TOO_LARGE_FOR_FARM,
    POOR_PAYMENT_HISTORY,
    CREDIT_BLOCKED
}
