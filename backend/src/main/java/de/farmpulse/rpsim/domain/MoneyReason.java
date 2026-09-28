package de.farmpulse.rpsim.domain;

/** reason enum of MONEY_TRANSACTION. */
public enum MoneyReason {
    CREDIT_DISBURSEMENT,
    CREDIT_INSTALLMENT,
    CREDIT_PENALTY,
    CREDIT_CALLBACK,
    SALARY_PAYMENT,
    EMPLOYEE_EFFECT,
    SUBSIDY,
    STARTING_CAPITAL_ADJUSTMENT,
    FARMLAND_PURCHASE,
    FARMLAND_SALE,
    // TODO T-20 / T-22
    INSURANCE_PREMIUM,
    INSURANCE_PAYOUT,
    DAMAGE,
    WILDLIFE_COMPENSATION,
    VET_INVOICE,
    LIVESTOCK_PREMIUM,
    LEASE_PAYMENT,
    MAINTENANCE_FEE,
    // Roadmap V2 (R2-Q1): tax office (E1), authority (E2), family (E3), clubs (E4), vanilla field purchase (D2)
    TAX_PAYMENT,
    TAX_REFUND,
    FINE,
    FAMILY,
    SPONSORING,
    COMPENSATION,
    OTHER
}
