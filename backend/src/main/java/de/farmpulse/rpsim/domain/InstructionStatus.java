package de.farmpulse.rpsim.domain;

/** Outbox status, updated from instructions_ack.json. */
public enum InstructionStatus {
    PENDING,
    APPLIED,
    REJECTED,
    FAILED
}
