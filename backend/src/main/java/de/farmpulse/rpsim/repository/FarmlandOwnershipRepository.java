package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FarmlandOwnershipRepository extends JpaRepository<FarmlandOwnership, Long> {

    List<FarmlandOwnership> findBySavegameOrderByFarmlandIdAsc(Savegame savegame);

    Optional<FarmlandOwnership> findBySavegameAndFarmlandId(Savegame savegame, int farmlandId);

    List<FarmlandOwnership> findBySavegameAndOwnerType(Savegame savegame, OwnerType ownerType);

    List<FarmlandOwnership> findByOwnerCharacter(Character character);
}
