package de.farmpulse.rpsim.domain;

/** Tradeable goods of the (generic) negotiation engine. */
public enum AssetType {
    FARMLAND,
    /** Roadmap V3 R3-V2 / R3-V3: a used machine; the asset id is the id of its {@link VehicleDeal}. */
    VEHICLE
}
