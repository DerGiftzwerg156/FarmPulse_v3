package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V2 R2-C1: crop of a field in one FS25 year (needed for E2) and how its harvest went. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_crop_history")
public class FieldCropHistory extends SavegameScoped {

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    /** FS25 year (calendar.year of the export). */
    @Column(name = "crop_year", nullable = false)
    private int cropYear;

    @Column(name = "fruit_type", nullable = false, length = 64)
    private String fruitType;

    @Column(name = "first_seen_game_time", nullable = false)
    private long firstSeenGameTime;

    @Column(name = "harvestable_seen", nullable = false)
    private boolean harvestableSeen;

    @Column(name = "harvested", nullable = false)
    private boolean harvested;

    @Column(name = "withered", nullable = false)
    private boolean withered;
}
