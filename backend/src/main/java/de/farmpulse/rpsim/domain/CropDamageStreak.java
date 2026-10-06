package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D5: samples in a row of one vehicle on one neighbour field with a crop. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "crop_damage_streak")
public class CropDamageStreak extends SavegameScoped {

    @Column(name = "vehicle_id", nullable = false, length = 64)
    private String vehicleId;

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "samples", nullable = false)
    private int samples;

    @Column(name = "last_game_time", nullable = false)
    private long lastGameTime;

    /** This streak already led to a hint or complaint. */
    @Column(name = "reported", nullable = false)
    private boolean reported;

    /** The incident of this row (its samples and an open claim grow while the row goes on). */
    @Column(name = "incident_id")
    private Long incidentId;
}
