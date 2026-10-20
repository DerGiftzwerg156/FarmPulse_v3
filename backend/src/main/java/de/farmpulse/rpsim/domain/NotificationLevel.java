package de.farmpulse.rpsim.domain;

/**
 * Level of a {@code NOTIFICATION} instruction - exactly the values the mod accepts ({@code FSBaseMission
 * .INGAME_NOTIFICATION_*}, bridge-protocol.md, {@code RPSimInstructions.NOTIFICATION_LEVELS}). Logic review LR-H2: an
 * enum instead of free text, so an unknown level (the mod rejects it and the hint is never shown) cannot be written.
 */
public enum NotificationLevel {
    INFO,
    OK,
    CRITICAL
}
