package de.farmpulse.rpsim.domain;

/**
 * Roadmap V2 R2-C1: growth phase of a field, derived from the FS25 field state. HARVESTED (stubble after the harvest,
 * FS25 {@code FruitTypeDesc:getIsCut}) is kept apart from WITHERED ({@code getIsWithered}); an older mod without these
 * flags gets the roadmap rule "above maxHarvestingGrowthState = WITHERED".
 */
public enum FieldPhase {
    EMPTY,
    GROWING,
    HARVESTABLE,
    HARVESTED,
    WITHERED;

    /** A crop stands on the field (hail, wildlife and the bank count it). */
    public boolean standing() {
        return this == GROWING || this == HARVESTABLE;
    }

    /** Nothing grows on the field (fallow for the village gossip, plowing / lime hints). */
    public boolean bare() {
        return this == EMPTY || this == HARVESTED;
    }
}
