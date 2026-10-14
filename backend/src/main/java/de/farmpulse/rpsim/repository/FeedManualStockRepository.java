package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FeedManualStock;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedManualStockRepository extends JpaRepository<FeedManualStock, Long> {

    List<FeedManualStock> findBySavegameOrderByIdAsc(Savegame savegame);
}
