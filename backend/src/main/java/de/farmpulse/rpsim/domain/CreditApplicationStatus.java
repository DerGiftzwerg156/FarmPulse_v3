package de.farmpulse.rpsim.domain;

/** PROCESSING until decisionVisibleAtGameTime, then DECIDED. */
public enum CreditApplicationStatus {
    PROCESSING,
    DECIDED,
    ACCEPTED,
    DECLINED
}
