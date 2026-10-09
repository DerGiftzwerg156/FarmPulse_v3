package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.3 R33-F5: litres the game counted for an entry of a closed harvest year. Not booked while the year is
 * closed; taken over when it is reopened.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_book_notice")
public class FieldBookNotice extends SavegameScoped {

    @Column(name = "entry_id", nullable = false)
    private long entryId;

    @Column(name = "liters", nullable = false)
    private double liters;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;
}
