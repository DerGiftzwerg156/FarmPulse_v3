package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.StoryHook;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryHookRepository extends JpaRepository<StoryHook, Long> {

    List<StoryHook> findBySavegameOrderByScheduledGameTimeAsc(Savegame savegame);

    List<StoryHook> findBySavegameAndFiredFalseOrderByScheduledGameTimeAsc(Savegame savegame);
}
