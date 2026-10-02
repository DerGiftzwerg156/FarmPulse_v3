package de.farmpulse.rpsim.domain;

/** reason enum of MONEY_TRANSACTION. */
public enum MoneyReason {
    CREDIT_DISBURSEMENT,
    CREDIT_INSTALLMENT,
    CREDIT_PENALTY,
    CREDIT_CALLBACK,
    /** Sondertilgung and its fee above the yearly free limit. */
    CREDIT_SPECIAL_REPAYMENT,
    CREDIT_PREPAYMENT_FEE,
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
    /** Training of a machine operator. */
    TRAINING,
    // Roadmap V3 (R3-Q1): lease income (L), goods trade (H, M3), used vehicles (V), forward contract penalty (M2)
    LEASE_INCOME,
    GOODS_PURCHASE,
    GOODS_SALE,
    VEHICLE_PURCHASE,
    VEHICLE_SALE,
    CONTRACT_PENALTY,
    // Roadmap V3.1 (R31-Q1): contractor (A1), machine rent (A2), livestock trade (A3), winter service (A4), area payment
    // (B1), investment grant (B2), social insurance (B5), farm holidays (D6), cooperative shares and dividend (D7)
    CONTRACTOR_FEE,
    MACHINE_RENT,
    LIVESTOCK_PURCHASE,
    LIVESTOCK_SALE,
    WINTER_SERVICE,
    DIRECT_PAYMENT,
    INVESTMENT_GRANT,
    SOCIAL_INSURANCE,
    GUEST_INCOME,
    COOP_SHARES,
    COOP_DIVIDEND,
    OTHER
}
