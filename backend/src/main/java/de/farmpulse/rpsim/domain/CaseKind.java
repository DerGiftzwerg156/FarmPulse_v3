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
    COMPENSATION_CLAIM,
    /** Roadmap V2 R2-E1: prepayment, assessment or audit back payment of the tax office (pay by button). */
    TAX_BILL,
    /** Roadmap V2 R2-E2: announced inspection of the authority (rotation, cultivation duty, animal welfare). */
    AUTHORITY_INSPECTION,
    /** Roadmap V2 R2-E4: sponsoring request of a club with fixed tiers. */
    SPONSORING_REQUEST,
    /** Roadmap V2 R2-E4: invitation to a festival (accept / decline). */
    INVITATION
}
