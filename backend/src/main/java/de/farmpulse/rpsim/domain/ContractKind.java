package de.farmpulse.rpsim.domain;

/** Recurring contracts with a monthly payment (TODO T-20 / T-22). */
public enum ContractKind {
    /** Storm/hail insurance: monthly premium, payout after a damage report. */
    INSURANCE,
    /** Lease of an NPC field: monthly rent, field goes back at the end. */
    LEASE,
    /** Maintenance contract with the workshop: monthly fee, repairs of worn vehicles included. */
    MAINTENANCE,
    /** Roadmap V2 R2-E1: tax advisor - monthly fee, lower tax, reminders, fewer audits. */
    TAX_ADVISOR,
    /** Roadmap V3 R3-L1: own field leased out to a neighbour - monthly rent income, field comes back at the end. */
    LEASE_OUT,
    /**
     * Roadmap V3.1 R31-A4: winter service for the municipality - income: base fee per winter month plus a fee per snow
     * day, paid at every month start for the winter month that ended (WinterServiceService, not the generic billing).
     */
    WINTER_SERVICE
}
