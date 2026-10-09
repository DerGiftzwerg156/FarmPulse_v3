package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.3 R33-F3: last seen value of a harvest counter of the mod; the difference to the next value is booked. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_book_counter")
public class FieldBookCounter extends SavegameScoped {

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "fruit_type", nullable = false, length = 64)
    private String fruitType;

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "last_liters", nullable = false)
    private double lastLiters;
}
