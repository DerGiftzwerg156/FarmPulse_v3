package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FeedCounter;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedCounterRepository extends JpaRepository<FeedCounter, Long> {

    List<FeedCounter> findBySavegame(Savegame savegame);
}
