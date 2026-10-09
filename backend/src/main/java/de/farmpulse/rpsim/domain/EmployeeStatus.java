package de.farmpulse.rpsim.domain;

/**
 * Employee lifecycle. PENDING_START (owner decision 2026-10-06): hired, but the work starts only with the next month
 * (startsAtGameTime) - no helper, no salary, frozen needs, no actions until then; seasonal workers start at once.
 */
public enum EmployeeStatus {
    PENDING_START,
    ACTIVE,
    TERMINATED
}
