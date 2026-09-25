package de.farmpulse.rpsim.time;

/** Published once for every game day crossed by incoming snapshots (daily spawn rolls etc.). */
public record GameDayPassedEvent(Long savegameId, long dayIndex, long gameTime) {
}
