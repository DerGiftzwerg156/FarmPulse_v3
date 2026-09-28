package de.farmpulse.rpsim.field;

/** Roadmap V2 R2-C4: the FS25 year of the fields changed - {@code year} is the harvest year that just ended. */
public record FieldYearClosedEvent(Long savegameId, int year) {
}
