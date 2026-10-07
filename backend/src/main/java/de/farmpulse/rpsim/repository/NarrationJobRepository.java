package de.farmpulse.rpsim.repository;

import java.time.Instant;
import java.util.List;

import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NarrationJobRepository extends JpaRepository<NarrationJob, Long> {

    List<NarrationJob> findByStatusOrderByIdAsc(NarrationJobStatus status);

    /**
     * Technical review 10/2026, Phase 1.6: jobs the worker may claim now - pending ones whose game time has come and
     * claimed ones whose lease ran out.
     */
    @Query("""
            select j.id from NarrationJob j
            where (j.status = de.farmpulse.rpsim.domain.NarrationJobStatus.PENDING
                   and j.notBeforeGameTime <= j.savegame.currentGameTime)
               or (j.status = de.farmpulse.rpsim.domain.NarrationJobStatus.IN_PROGRESS and j.leaseUntil < :now)
            order by j.id""")
    List<Long> findClaimableIds(Instant now);

    List<NarrationJob> findBySavegameOrderByIdAsc(Savegame savegame);

    List<NarrationJob> findBySavegameAndEventTypeOrderByIdAsc(Savegame savegame, String eventType);
}
