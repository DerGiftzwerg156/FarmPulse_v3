package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3 R3-H2: stock of a neighbour per fill type (litres) - backend fiction fed by his real fields. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "neighbor_stock")
public class NeighborStock extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "amount", nullable = false)
    private double amount;

    @Column(name = "updated_game_time", nullable = false)
    private long updatedGameTime;
}
