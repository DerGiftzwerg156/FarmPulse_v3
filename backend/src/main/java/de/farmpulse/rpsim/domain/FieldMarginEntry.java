package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V4 R4-D2: per field book entry the best price of its product on the harvest day (euro per litre, taken when
 * the entry ended at {@code endedGameTime}) and the price the player entered; R4-D6: whether the cooperative wrote
 * about two loss years in a row ending with this entry.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_margin_entry")
public class FieldMarginEntry extends SavegameScoped {

    @Column(name = "entry_id", nullable = false)
    private long entryId;

    @Column(name = "ended_game_time")
    private Long endedGameTime;

    @Column(name = "harvest_price")
    private Double harvestPrice;

    @Column(name = "price_manual")
    private Double priceManual;

    @Column(name = "advised", nullable = false)
    private boolean advised;
}
