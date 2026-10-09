package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-D5: a complaint of a field owner (a claim case on a repetition). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "crop_damage_event")
public class CropDamageEvent extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "samples", nullable = false)
    private int samples;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "claim_case_id")
    private Long claimCaseId;
}
