package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.PublicActionEvent;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicActionEventRepository extends JpaRepository<PublicActionEvent, Long> {

    List<PublicActionEvent> findBySavegameOrderByGameTimeAsc(Savegame savegame);

    /** Roadmap V3 R3-H4: capped reputation per FS25 year. */
    long countBySavegameAndTypeAndGameTimeGreaterThanEqual(Savegame savegame, PublicActionType type, long gameTime);
}
