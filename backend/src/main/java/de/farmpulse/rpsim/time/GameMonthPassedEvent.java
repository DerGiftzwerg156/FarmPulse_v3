package de.farmpulse.rpsim.time;

/** Published once for every game month (= FS25 period, TODO T-08) crossed (monthly effects). */
public record GameMonthPassedEvent(Long savegameId, long monthIndex, long gameTime) {
}
