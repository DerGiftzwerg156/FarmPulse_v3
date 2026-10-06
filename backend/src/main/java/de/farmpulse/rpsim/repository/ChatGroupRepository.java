package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.ChatGroup;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatGroupRepository extends JpaRepository<ChatGroup, Long> {

    List<ChatGroup> findBySavegameOrderByIdAsc(Savegame savegame);

    Optional<ChatGroup> findBySavegameAndGroupKey(Savegame savegame, String groupKey);
}
