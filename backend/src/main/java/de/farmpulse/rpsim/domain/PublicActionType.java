package de.farmpulse.rpsim.domain;

/** Public actions feeding village reputation. */
public enum PublicActionType {
    PUBLIC_DEFAULT,
    VILLAGE_EVENT,
    DONATION,
    /** TODO T-20: wildlife damage settled together with a joint measure (hunt / fence) - positive. */
    WILDLIFE_MEASURE,
    /** TODO T-20: wildlife compensation refused - a public dispute with the hunter. */
    WILDLIFE_DISPUTE,
    /** Roadmap V2 R2-D2: a field of a villager bought over their head in the game menu. */
    FIELD_BYPASS,
    /** Roadmap V2 R2-E: sponsoring of a club (positive), fine of the authority (negative). */
    SPONSORING,
    AUTHORITY_FINE,
    /** Roadmap V3 R3-H4: goods sold to a neighbour who asked for them (positive, capped per FS25 year). */
    NEIGHBOR_HELP,
    /** Roadmap V3 R3-M3: a farm-shop order of a villager delivered (positive, capped per FS25 year). */
    FARM_SHOP,
    /** Roadmap V3.1 R31-D3: never at the regulars' table ("eigenbrötlerisch", negative, capped). */
    STAMMTISCH_LONER,
    /** Roadmap V3.1 R31-D6: a school class visited the farm (positive). */
    SCHOOL_VISIT,
    /** Roadmap V3.1 R31-D7: cooperative - festival sponsoring of the assembly, the board seat (positive). */
    COOPERATIVE,
    /** Roadmap V3.2 R32-I3 P5: an investor named in the village paper (+/- by kind), a breach or termination (-). */
    INVESTOR_NAMED,
    INVESTOR_BREACH,
    OTHER
}
