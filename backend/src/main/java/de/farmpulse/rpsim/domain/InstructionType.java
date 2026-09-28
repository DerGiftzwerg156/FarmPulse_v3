package de.farmpulse.rpsim.domain;

/** Bridge instruction types. */
public enum InstructionType {
    MONEY_TRANSACTION,
    PRICE_EVENT,
    FARMLAND_TRANSFER,
    /** TODO T-21: in-game notification (new mail / incoming call); never re-sent after a rewind, no failure notice. */
    NOTIFICATION,
    /**
     * TODO T-22: repair of an own vehicle paid by the maintenance contract (Wearable:setDamageAmount(0, true)).
     * Not re-sent after a rewind - the workshop repairs the still damaged vehicle at the next monthly service.
     */
    REPAIR_VEHICLE,
    /**
     * Roadmap V2 R2-A0: complete employee list for the mod (helpers driven by employees). Sent when it changes and after
     * a rewind; a refusal (older mod) creates no notice.
     */
    EMPLOYEE_ROSTER,
    /**
     * Roadmap V2 R2-F2: yes/no question shown in the game; the answer comes back in export/player_responses.json. Sent
     * again after a rewind while the question is still open; a refusal (older mod) creates no notice.
     */
    PROMPT
}
