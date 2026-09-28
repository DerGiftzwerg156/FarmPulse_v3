package de.farmpulse.rpsim.domain;

/** Simulated incidents and one-off offers handled by the service characters (TODO T-20 / T-22). */
public enum CaseKind {
    STORM_DAMAGE,
    HAIL_DAMAGE,
    WILDLIFE_DAMAGE,
    VET_VISIT,
    LIVESTOCK_OFFER,
    BREEDING_ADVICE,
    REPAIR,
    MISSION_REFERRAL,
    /** Roadmap V2 R2-D2: the former owner of a field bought in the game menu claims a compensation. */
    COMPENSATION_CLAIM
}
