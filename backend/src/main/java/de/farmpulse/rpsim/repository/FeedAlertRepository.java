package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FeedAlert;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedAlertRepository extends JpaRepository<FeedAlert, Long> {

    List<FeedAlert> findBySavegame(Savegame savegame);
}
