package de.farmpulse.rpsim.domain;

/** Roles of generated characters. */
public enum CharacterRole {
    BANK_ADVISOR,
    COOPERATIVE,
    LAND_AGENT,
    AUTHORITY,
    NEIGHBOR_FARMER,
    VILLAGER,
    SUPPLIER,
    EMPLOYEE,
    APPLICANT,
    // TODO T-20: service characters (mandatory roles, created when their first occasion arises)
    INSURANCE_AGENT,
    HUNTER,
    VETERINARIAN,
    LIVESTOCK_TRADER,
    BREEDING_ADVISOR,
    ENERGY_SUPPLIER,
    // TODO T-22: contract partners
    WORKSHOP,
    CONTRACTOR,
    // Roadmap V2 R2-E: tax office, tax advisor, family, clubs
    TAX_OFFICE,
    TAX_ADVISOR,
    FAMILY,
    CLUB,
    /** Roadmap V3.1 R31-B5: the agricultural social insurance (Berufsgenossenschaft). */
    SOCIAL_INSURANCE,
    /** Roadmap V3.1 R31-D8: the village police (diesel theft). */
    POLICE,
    /** Roadmap V3.1 R31-D6: the teacher of the village school (school visits). */
    SCHOOL,
    /**
     * Roadmap V3.2 R32-Q1: a bulk buyer tied to a sell point of the map (bulk orders, R32-G1); created when needed like
     * an applicant, not part of the starting cast.
     */
    BULK_BUYER,
    /** Roadmap V3.2 R32-Q1: a large investor (R32-I1); created with the offer, not part of the starting cast. */
    INVESTOR
}
