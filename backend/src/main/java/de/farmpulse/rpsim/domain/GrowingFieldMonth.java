package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3 R3-W2: an own field that had a crop in phase GROWING in a game month (recorded from the field export of
 * R2-C1); the drought aid pays per hectare of these fields in the drought months.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "growing_field_month")
public class GrowingFieldMonth extends SavegameScoped {

    @Column(name = "month_index", nullable = false)
    private long monthIndex;

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "field_name", length = 128)
    private String fieldName;

    @Column(name = "fruit_type", length = 64)
    private String fruitType;

    @Column(name = "hectares", nullable = false)
    private double hectares;
}
