package de.farmpulse.rpsim.employee;

/** Roadmap V2 R2-A0: the employee list changed (hire, dismissal, strike, time off, settings) - send it to the mod. */
public record RosterChangedEvent(Long savegameId) {
}
