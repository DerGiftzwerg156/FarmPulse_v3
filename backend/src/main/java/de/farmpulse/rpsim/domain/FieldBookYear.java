package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.3 R33-F5: a closed harvest year of the field book (locked for changes and the automatic capture). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_book_year")
public class FieldBookYear extends SavegameScoped {

    @Column(name = "harvest_year", nullable = false)
    private int harvestYear;

    @Column(name = "closed_game_time", nullable = false)
    private long closedGameTime;
}
