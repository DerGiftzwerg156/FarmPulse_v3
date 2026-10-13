package de.farmpulse.rpsim.field;

/** Roadmap V3.3 R33-F5 / V4 R4-D4: a closed harvest year was reopened; what was held back for it is taken over. */
public record FieldYearReopenedEvent(Long savegameId, int year) {
}
