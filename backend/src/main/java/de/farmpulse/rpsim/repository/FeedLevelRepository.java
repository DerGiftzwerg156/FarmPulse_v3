package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FeedLevel;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedLevelRepository extends JpaRepository<FeedLevel, Long> {

    List<FeedLevel> findBySavegame(Savegame savegame);
}
