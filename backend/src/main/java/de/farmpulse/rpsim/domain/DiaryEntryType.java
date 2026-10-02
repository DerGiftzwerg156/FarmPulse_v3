package de.farmpulse.rpsim.domain;

/** Automatic chronicle entry or free player note (no mechanical effect). */
public enum DiaryEntryType {
    AUTO,
    PLAYER_NOTE,
    /** Roadmap V3 R3-T1: a reached milestone. */
    MILESTONE
}
