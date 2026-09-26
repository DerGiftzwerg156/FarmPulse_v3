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
    CONTRACTOR
}
