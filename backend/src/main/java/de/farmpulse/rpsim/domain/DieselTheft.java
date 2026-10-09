package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D8: a planned / executed diesel theft (VEHICLE_FUEL at night). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "diesel_theft")
public class DieselTheft extends SavegameScoped {

    public static final String PLANNED = "PLANNED";
    public static final String SENT = "SENT";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Column(name = "vehicle_id", nullable = false, length = 64)
    private String vehicleId;

    @Column(name = "vehicle_name", length = 255)
    private String vehicleName;

    /** Planned amount. */
    @Column(name = "liters", nullable = false)
    private long liters;

    /** PLANNED, SENT, DONE, FAILED. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "planned_game_time", nullable = false)
    private long plannedGameTime;

    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    @Column(name = "stolen_liters")
    private Long stolenLiters;

    @Column(name = "damage")
    private Long damage;

    @Column(name = "insurance_payout")
    private Long insurancePayout;

    @Column(name = "closed_game_time")
    private Long closedGameTime;
}
