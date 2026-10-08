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
    PROMPT,
    /**
     * Roadmap V3 (R3-Q1): goods into (IN, purchase) or out of (OUT, sale) the own silos, always in a batch with its
     * MONEY_TRANSACTION (R3-H3/H4/M3). A refusal (older mod) cancels the deal with the notice "Mod aktualisieren".
     */
    STORAGE_TRANSFER,
    /** Roadmap V3 (R3-Q1): real contract of the game on the field of a neighbour (R3-H5); result.missionId in the ack. */
    MISSION_CREATE,
    /**
     * Roadmap V3 (R3-Q1): used vehicle from the shop catalog; the mod books the price itself in the loading callback
     * (R3-V2); result.vehicleId in the ack.
     */
    VEHICLE_SPAWN,
    /** Roadmap V3 (R3-Q1): own vehicle removed after a sale, in a batch with its MONEY_TRANSACTION (R3-V3). */
    VEHICLE_REMOVE,
    /**
     * Roadmap V3.1 (R31-Q1): end state of a contractor's work (PLOW, CULTIVATE, LIME, SOW, HARVEST) on an own field, in
     * a batch with its MONEY_TRANSACTION CONTRACTOR_FEE (R31-A1).
     */
    FIELD_WORK,
    /**
     * Roadmap V3.1 (R31-Q1): animals of one subtype into (IN) or out of (OUT) an own husbandry, in a batch with its
     * MONEY_TRANSACTION LIVESTOCK_PURCHASE / LIVESTOCK_SALE (R31-A3).
     */
    ANIMAL_TRANSFER,
    /** Roadmap V3.1 (R31-Q1): diesel taken out of an own vehicle (diesel theft, R31-D8); result.liters in the ack. */
    VEHICLE_FUEL,
    /**
     * Roadmap V3.2 (R32-Q1): milk taken out of the storage of an own husbandry (only taking out; milk delivery to an
     * investor, R32-I3 type W3). An own type instead of a field of STORAGE_TRANSFER: an older mod would skip an unknown
     * field and book from the silos, an unknown type is refused (notice "Mod aktualisieren").
     */
    HUSBANDRY_TRANSFER
}
