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
    INVITATION,
    /** Roadmap V3 R3-H3: a neighbour offers goods of his stock (on his own or asked by the player) - the player buys. */
    GOODS_OFFER,
    /** Roadmap V3 R3-H4: a neighbour asks for goods of the player's silos - the player sells. */
    GOODS_REQUEST,
    /** Roadmap V3 R3-H5: a neighbour asks for help with a real contract of the game on his field. */
    NEIGHBOR_MISSION,
    /** Roadmap V3 R3-K1: a pledged field was sold in the game menu - the bank claims a Sondertilgung (pay by button). */
    COLLATERAL_CLAIM,
    /** Roadmap V3 R3-K3: invitation of the bank advisor to the annual review (attend / decline). */
    ANNUAL_REVIEW,
    /** Roadmap V3 R3-K3: rate cut offered in the annual review (accept / decline). */
    ANNUAL_REVIEW_OFFER,
    /** Roadmap V3 R3-M3: a villager orders goods of the own silos at the farm-shop price (deliver / decline). */
    FARM_SHOP_ORDER,
    /** Roadmap V3 R3-W2: drought aid of the authority after a declared drought (apply by button within the deadline). */
    DROUGHT_AID,
    /** Roadmap V3 R3-P2: the apprentice asks to be taken over as machine operator (accept / counter offer / decline). */
    APPRENTICE_TAKEOVER
}
