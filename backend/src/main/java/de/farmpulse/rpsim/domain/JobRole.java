package de.farmpulse.rpsim.domain;

/** Employee roles with role specific skills. */
public enum JobRole {
    MACHINE_OPERATOR,
    MECHANIC,
    ANIMAL_KEEPER,
    OFFICE_CLERK,
    /** Roadmap V3 R3-P2: drives helpers like a machine operator without trainings; taken over after the training. */
    APPRENTICE,
    /**
     * Roadmap V3.1 R31-A5: fixed-term seasonal worker for the harvest; drives helpers like a machine operator without
     * trainings, no salary negotiation.
     */
    SEASONAL_WORKER;

    /** Roles that drive FS25 helpers (worked hours, roster role for the mod). */
    public boolean drives() {
        return this == MACHINE_OPERATOR || this == APPRENTICE || this == SEASONAL_WORKER;
    }
}
