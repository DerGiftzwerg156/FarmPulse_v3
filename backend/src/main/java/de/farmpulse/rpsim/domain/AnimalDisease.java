package de.farmpulse.rpsim.domain;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.1 R31-B4: an animal disease in the region with a restricted zone for the animal types
 * ({@code animalTypes}, comma separated) from the declaration until the month {@code endsMonthIndex} starts; LIFTED
 * afterwards (the neighbour trade recovers its prices from {@code liftedMonthIndex} on).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "animal_disease")
public class AnimalDisease extends SavegameScoped {

    public static final String ACTIVE = "ACTIVE";
    public static final String LIFTED = "LIFTED";

    @Column(name = "disease_key", nullable = false, length = 32)
    private String diseaseKey;

    @Column(name = "animal_types", nullable = false, length = 255)
    private String animalTypes;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "declared_game_time", nullable = false)
    private long declaredGameTime;

    @Column(name = "declared_month_index", nullable = false)
    private long declaredMonthIndex;

    @Column(name = "ends_month_index", nullable = false)
    private long endsMonthIndex;

    @Column(name = "lifted_game_time")
    private Long liftedGameTime;

    @Column(name = "lifted_month_index")
    private Long liftedMonthIndex;

    public List<String> types() {
        return Arrays.stream(animalTypes.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
