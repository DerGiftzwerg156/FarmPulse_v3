package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketEventRepository extends JpaRepository<MarketEvent, Long> {

    List<MarketEvent> findBySavegameAndStatusIn(Savegame savegame, Collection<MarketEventStatus> statuses);

    List<MarketEvent> findBySavegameOrderByIdDesc(Savegame savegame);

    Optional<MarketEvent> findByInstructionId(String instructionId);
}
