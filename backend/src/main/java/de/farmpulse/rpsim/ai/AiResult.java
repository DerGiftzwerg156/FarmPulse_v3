package de.farmpulse.rpsim.ai;

/** Structured provider output {"subject": "...", "body": "..."} - text only, never a number that drives the game. */
public record AiResult(String subject, String body) {
}
