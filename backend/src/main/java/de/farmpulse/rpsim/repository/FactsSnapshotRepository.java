package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactsSnapshotRepository extends JpaRepository<FactsSnapshot, Long> {

    Optional<FactsSnapshot> findFirstBySavegameOrderByGameTimeDescIdDesc(Savegame savegame);

    List<FactsSnapshot> findBySavegameAndGameTimeBetweenOrderByGameTimeAscIdAsc(Savegame savegame, long from, long to);

    Optional<FactsSnapshot> findFirstBySavegameAndGameTimeLessThanEqualOrderByGameTimeDescIdDesc(Savegame savegame, long gameTime);

    long countBySavegame(Savegame savegame);
}
