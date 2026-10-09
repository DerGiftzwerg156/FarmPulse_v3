package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.ChatMember;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMemberRepository extends JpaRepository<ChatMember, Long> {

    List<ChatMember> findBySavegameOrderByIdAsc(Savegame savegame);

    List<ChatMember> findByGroupIdOrderByIdAsc(Long groupId);
}
