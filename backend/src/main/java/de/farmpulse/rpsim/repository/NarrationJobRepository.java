package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.NarrationJobStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NarrationJobRepository extends JpaRepository<NarrationJob, Long> {

    List<NarrationJob> findByStatusOrderByIdAsc(NarrationJobStatus status);

    List<NarrationJob> findBySavegameOrderByIdAsc(Savegame savegame);

    List<NarrationJob> findBySavegameAndEventTypeOrderByIdAsc(Savegame savegame, String eventType);
}
