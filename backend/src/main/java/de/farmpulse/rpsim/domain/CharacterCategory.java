package de.farmpulse.rpsim.domain;

/** Mandatory roles are never vacant; dynamic characters rotate; employees/applicants belong to the employee system. */
public enum CharacterCategory {
    MANDATORY,
    DYNAMIC,
    EMPLOYEE,
    APPLICANT,
    SUBSTITUTE
}
