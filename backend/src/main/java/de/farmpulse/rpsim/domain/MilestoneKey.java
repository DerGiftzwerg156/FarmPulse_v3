package de.farmpulse.rpsim.domain;

/** Roadmap V3 R3-T1: the fixed list of milestones (owner decision in QUESTIONS.md). No mechanical effect. */
public enum MilestoneKey {
    /** The first bank loan fully repaid (loans from the game menu do not count). */
    LOAN_REPAID,
    /** A full FS25 year without a payment delay. */
    YEAR_WITHOUT_DELAY,
    /** The area of all fields of the farm reaches the configured hectares. */
    AREA,
    /** The first congratulation of the cooperative on a record harvest revenue (R2-B5). */
    RECORD_HARVEST,
    /** The configured number of harvest years in a row without a crop-rotation complaint (R2-E2). */
    CROP_ROTATION,
    /** The first completed goods trade with a neighbour (R3-H3 / R3-H4). */
    NEIGHBOR_TRADE
}
