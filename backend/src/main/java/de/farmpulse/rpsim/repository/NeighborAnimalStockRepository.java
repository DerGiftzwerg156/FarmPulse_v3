package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.NeighborAnimalStock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NeighborAnimalStockRepository extends JpaRepository<NeighborAnimalStock, Long> {

    List<NeighborAnimalStock> findByCharacterOrderByAnimalTypeAsc(Character character);

    Optional<NeighborAnimalStock> findByCharacterAndAnimalType(Character character, String animalType);
}
