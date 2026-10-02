package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Milestone;
import de.farmpulse.rpsim.domain.MilestoneKey;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MilestoneRepository extends JpaRepository<Milestone, Long> {

    List<Milestone> findBySavegameOrderByReachedGameTimeAscIdAsc(Savegame savegame);

    boolean existsBySavegameAndMilestoneKey(Savegame savegame, MilestoneKey milestoneKey);
}
