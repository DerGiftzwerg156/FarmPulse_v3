package de.farmpulse.rpsim.domain;

/** Technical concept "Anruf-Zustandsautomat". */
public enum CallStatus {
    RINGING,
    ACCEPTED,
    DECLINED,
    MISSED,
    COMPLETED
}
