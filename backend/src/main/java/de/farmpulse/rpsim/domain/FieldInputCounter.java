package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V4 R4-D1: last seen value of a consumption counter of the mod; the difference to the next value is booked. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_input_counter")
public class FieldInputCounter extends SavegameScoped {

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "last_liters", nullable = false)
    private double lastLiters;

    @Column(name = "last_helper_liters", nullable = false)
    private double lastHelperLiters;
}
