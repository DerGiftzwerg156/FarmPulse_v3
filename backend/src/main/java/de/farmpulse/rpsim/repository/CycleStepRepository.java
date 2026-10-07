package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.CycleStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CycleStepRepository extends JpaRepository<CycleStep, Long> {

    Optional<CycleStep> findByCycleEventIdAndStepKeyAndListenerId(Long cycleEventId, String stepKey, String listenerId);

    List<CycleStep> findByCycleEventIdOrderByIdAsc(Long cycleEventId);

    @Modifying
    @Query("delete from CycleStep s where s.cycleEventId = :eventId")
    void deleteByCycleEventId(Long eventId);
}
