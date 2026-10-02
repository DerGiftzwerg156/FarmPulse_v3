package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Roadmap V3.1 R31-A3: animals a neighbour keeps of one animal type (backend fiction, rolled when first needed). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "neighbor_animal_stock")
public class NeighborAnimalStock extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id")
    private Character character;

    /** Animal type as in assets.animals (e.g. COW). */
    @Column(name = "animal_type", nullable = false, length = 32)
    private String animalType;

    @Column(name = "animal_count", nullable = false)
    private int count;

    @Column(name = "updated_game_time", nullable = false)
    private long updatedGameTime;
}
