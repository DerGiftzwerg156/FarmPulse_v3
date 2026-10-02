package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.NeighborStock;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NeighborStockRepository extends JpaRepository<NeighborStock, Long> {

    List<NeighborStock> findBySavegameOrderByIdAsc(Savegame savegame);

    List<NeighborStock> findByCharacterOrderByFillTypeAsc(Character character);

    Optional<NeighborStock> findByCharacterAndFillType(Character character, String fillType);
}
