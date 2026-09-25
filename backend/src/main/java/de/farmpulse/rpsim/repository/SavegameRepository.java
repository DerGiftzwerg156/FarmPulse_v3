package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.SavegameStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavegameRepository extends JpaRepository<Savegame, Long> {

    Optional<Savegame> findByBridgeSavegameId(String bridgeSavegameId);

    List<Savegame> findByStatus(SavegameStatus status);

    Optional<Savegame> findFirstByStatusOrderByLinkedAtDesc(SavegameStatus status);
}
