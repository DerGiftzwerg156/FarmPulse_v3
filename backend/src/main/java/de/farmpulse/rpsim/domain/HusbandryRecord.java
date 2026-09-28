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
}
