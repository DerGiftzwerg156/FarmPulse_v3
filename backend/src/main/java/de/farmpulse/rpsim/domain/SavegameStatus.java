package de.farmpulse.rpsim.domain;

/** Lifecycle of a savegame: DRAFT during onboarding, ACTIVE once linked. */
public enum SavegameStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
