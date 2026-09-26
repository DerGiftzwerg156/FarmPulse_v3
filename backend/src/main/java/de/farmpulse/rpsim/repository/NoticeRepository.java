package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Notice;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.NoticeStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    List<Notice> findBySavegameAndStatusOrderByIdDesc(Savegame savegame, NoticeStatus status);

    List<Notice> findBySavegameOrderByIdDesc(Savegame savegame);

    Optional<Notice> findFirstBySavegameAndKindAndRelatedEntityTypeAndRelatedEntityId(Savegame savegame, NoticeKind kind,
                                                                                       String relatedType, Long relatedId);
}
