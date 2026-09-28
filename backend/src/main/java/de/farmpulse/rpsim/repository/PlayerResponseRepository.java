package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;

import de.farmpulse.rpsim.domain.PlayerResponse;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerResponseRepository extends JpaRepository<PlayerResponse, Long> {

    boolean existsBySavegameAndResponseId(Savegame savegame, String responseId);

    List<PlayerResponse> findBySavegameAndResponseIdIn(Savegame savegame, Collection<String> responseIds);
}
