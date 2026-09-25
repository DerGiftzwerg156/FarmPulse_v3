package de.farmpulse.rpsim.time;

/**
 * Published after every ingested snapshot whose game time is later than the previous one. All game-time
 * driven logic (payroll, escalations, call ring timeouts, delayed decisions) reacts to it - there is no
 * real-time cron (technical concept "PayrollScheduler").
 */
public record GameTimeAdvancedEvent(Long savegameId, long previousGameTime, long gameTime) {
}
