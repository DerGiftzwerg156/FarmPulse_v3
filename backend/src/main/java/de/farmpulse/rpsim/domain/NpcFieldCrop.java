package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3 R3-H1: crop of a neighbour field per FS25 year (the fallback of R3-H1 reads the last one seen). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "npc_field_crop")
public class NpcFieldCrop extends SavegameScoped {

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "crop_year", nullable = false)
    private int cropYear;

    @Column(name = "fruit_type", nullable = false, length = 64)
    private String fruitType;

    @Column(name = "first_seen_game_time", nullable = false)
    private long firstSeenGameTime;

    @Column(name = "harvested", nullable = false)
    private boolean harvested;
}
