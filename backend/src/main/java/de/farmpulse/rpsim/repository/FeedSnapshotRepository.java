package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FeedSnapshot;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedSnapshotRepository extends JpaRepository<FeedSnapshot, Long> {

    List<FeedSnapshot> findBySavegameOrderByGameTimeAsc(Savegame savegame);

    boolean existsBySavegameAndHusbandryUniqueId(Savegame savegame, String husbandryUniqueId);
}
