package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V2 R2-E2: a husbandry with bad values (animal welfare) - since when and how often it was a violation. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "husbandry_record")
public class HusbandryRecord extends SavegameScoped {

    @Column(name = "husbandry_unique_id", nullable = false, length = 64)
    private String husbandryUniqueId;

    @Column(name = "bad_since")
    private Long badSince;

    @Column(name = "violations", nullable = false)
    private int violations;

    /** Roadmap V3.1 R31-B3: slurry store above the warning ratio since; last warning of the keeper / cooperative. */
    @Column(name = "slurry_high_since")
    private Long slurryHighSince;

    @Column(name = "last_slurry_warning_game_time")
    private Long lastSlurryWarningGameTime;
}
