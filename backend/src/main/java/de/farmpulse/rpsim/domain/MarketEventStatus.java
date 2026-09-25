package de.farmpulse.rpsim.domain;

/** Lifecycle of market events. */
public enum MarketEventStatus {
    PLANNED,
    OFFERED,
    ACTIVE,
    ENDED,
    DECLINED,
    RUMOR_ONLY
}
