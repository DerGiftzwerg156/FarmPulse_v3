package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.CycleEvent;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CycleEventRepository extends JpaRepository<CycleEvent, Long> {

    Optional<CycleEvent> findFirstBySavegameIdOrderByIdAsc(Long savegameId);

    boolean existsBySavegameId(Long savegameId);

    @Query("select distinct e.savegame.id from CycleEvent e")
    List<Long> savegamesWithWork();
}
