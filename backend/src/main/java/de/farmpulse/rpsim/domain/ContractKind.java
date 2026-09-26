package de.farmpulse.rpsim.domain;

/** Recurring contracts with a monthly payment (TODO T-20 / T-22). */
public enum ContractKind {
    /** Storm/hail insurance: monthly premium, payout after a damage report. */
    INSURANCE,
    /** Lease of an NPC field: monthly rent, field goes back at the end. */
    LEASE,
    /** Maintenance contract with the workshop: monthly fee, repairs of worn vehicles included. */
    MAINTENANCE
}
