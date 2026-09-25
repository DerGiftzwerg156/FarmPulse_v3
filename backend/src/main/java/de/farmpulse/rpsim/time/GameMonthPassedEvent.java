package de.farmpulse.rpsim.time;

/** Published once for every game month crossed (monthly effects, rotation year reset). */
public record GameMonthPassedEvent(Long savegameId, long monthIndex, long gameTime) {
}
