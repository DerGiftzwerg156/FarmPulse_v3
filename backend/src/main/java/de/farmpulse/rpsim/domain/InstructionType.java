package de.farmpulse.rpsim.domain;

/** Bridge instruction types. */
public enum InstructionType {
    MONEY_TRANSACTION,
    PRICE_EVENT,
    FARMLAND_TRANSFER,
    /** TODO T-21: in-game notification (new mail / incoming call); never re-sent after a rewind, no failure notice. */
    NOTIFICATION
}
