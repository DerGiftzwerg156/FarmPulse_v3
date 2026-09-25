package de.farmpulse.rpsim.domain;

/** Defined, logged trust events (never free AI interpretation). */
public enum TrustReason {
    ON_TIME_PAYMENT,
    MISSED_PAYMENT,
    PAYMENT_ESCALATION,
    PROMISE_KEPT,
    PROMISE_BROKEN,
    CALL_DECLINED,
    CALL_MISSED,
    TONE_FRIENDLY,
    TONE_RUDE,
    NEGOTIATION_DEAL,
    INITIAL,
    OTHER
}
