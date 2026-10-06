package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.PublicActionEvent;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicActionEventRepository extends JpaRepository<PublicActionEvent, Long> {

    List<PublicActionEvent> findBySavegameOrderByGameTimeAsc(Savegame savegame);

    /** Roadmap V3 R3-H4: capped reputation per FS25 year. */
    /** Roadmap V3.1 R31-D1: public actions of an issue window (from exclusive, to inclusive). */
    List<PublicActionEvent> findBySavegameAndGameTimeGreaterThanAndGameTimeLessThanEqualOrderByGameTimeAsc(Savegame savegame,
                                                                                                         long from, long to);

    long countBySavegameAndTypeAndGameTimeGreaterThanEqual(Savegame savegame, PublicActionType type, long gameTime);
}
