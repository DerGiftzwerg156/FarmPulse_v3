package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.ChatMessage;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySavegameOrderByIdAsc(Savegame savegame);

    List<ChatMessage> findByGroupIdOrderByIdDesc(Long groupId, Pageable page);

    boolean existsBySavegameAndRelatedCaseId(Savegame savegame, Long relatedCaseId);
}
