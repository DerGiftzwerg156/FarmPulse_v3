package de.farmpulse.rpsim.domain;

/** Defined, logged trust events (never free AI interpretation). */
public enum TrustReason {
    ON_TIME_PAYMENT,
    /** T-03: an installment rewarded as on time was not executed by the mod. */
    ON_TIME_PAYMENT_REVERSED,
    MISSED_PAYMENT,
    PAYMENT_ESCALATION,
    PROMISE_KEPT,
    PROMISE_BROKEN,
    CALL_DECLINED,
    CALL_MISSED,
    TONE_FRIENDLY,
    TONE_RUDE,
    NEGOTIATION_DEAL,
    /** TODO T-20: wildlife damage settled amicably / with a joint measure. */
    WILDLIFE_AGREEMENT,
    /** TODO T-20: compensation refused, dispute. */
    WILDLIFE_DISPUTE,
    /** TODO T-22: a contract referred by the contractor was completed / failed. */
    MISSION_COMPLETED,
    MISSION_FAILED,
    /** Roadmap V2 R2-B5: record harvest revenue of a month, the cooperative congratulates. */
    RECORD_HARVEST,
    /** Roadmap V2 R2-C4: a field stayed weedy / stony although the neighbor asked. */
    FIELD_NEGLECTED,
    /** Roadmap V2 R2-C4: every harvestable field of a year was harvested in time. */
    HARVEST_IN_TIME,
    /** Roadmap V2 R2-D1: a vanilla loan was taken past the bank / repaid. */
    VANILLA_LOAN,
    VANILLA_LOAN_REPAID,
    /** Roadmap V2 R2-D2: field bought over the owner's head in the game menu / compensation refused. */
    FIELD_BYPASS,
    COMPENSATION_DECLINED,
    INITIAL,
    OTHER
}
