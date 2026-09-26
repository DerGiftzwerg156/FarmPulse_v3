package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRepository extends JpaRepository<Character, Long> {

    List<Character> findBySavegameOrderByIdAsc(Savegame savegame);

    java.util.Optional<Character> findFirstBySavegameAndFs25NpcIndex(Savegame savegame, Integer fs25NpcIndex);

    List<Character> findBySavegameAndStatus(Savegame savegame, CharacterStatus status);

    List<Character> findBySavegameAndCategoryAndStatus(Savegame savegame, CharacterCategory category, CharacterStatus status);

    List<Character> findBySavegameAndRoleAndStatus(Savegame savegame, CharacterRole role, CharacterStatus status);

    List<Character> findBySavegameAndCategoryInAndStatusIn(Savegame savegame, List<CharacterCategory> categories,
                                                          List<CharacterStatus> statuses);
}
