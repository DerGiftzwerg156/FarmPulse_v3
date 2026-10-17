package de.farmpulse.rpsim.domain;

/** Tradeable goods of the (generic) negotiation engine. */
public enum AssetType {
    FARMLAND,
    /** Roadmap V3 R3-V2 / R3-V3: a used machine; the asset id is the id of its {@link VehicleDeal}. */
    VEHICLE,
    /**
     * Roadmap vehicle dealer RFH-B2: new machines of the dealer; the asset id is the id of the DealerOrder, the base price
     * the dealer's offer (net of trade-ins) and the asking price his hidden floor (owner decisions 2026-10-15).
     */
    MACHINE_ORDER
}
