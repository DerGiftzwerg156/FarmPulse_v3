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
    OTHER
}
