package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.GamePrompt;
import de.farmpulse.rpsim.domain.PromptStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GamePromptRepository extends JpaRepository<GamePrompt, Long> {

    Optional<GamePrompt> findBySavegameAndPromptKey(Savegame savegame, String promptKey);

    Optional<GamePrompt> findByPromptId(String promptId);

    List<GamePrompt> findBySavegameAndStatusOrderByIdAsc(Savegame savegame, PromptStatus status);

    List<GamePrompt> findBySavegameAndStatusInAndExpiresGameTimeGreaterThanEqualOrderByIdAsc(Savegame savegame,
            Collection<PromptStatus> status, long gameTime);

    List<GamePrompt> findBySavegameOrderByIdDesc(Savegame savegame);

    /** Answers read from the game that are not carried out yet. */
    List<GamePrompt> findByStatusAndResultIsNullOrderByIdAsc(PromptStatus status);
}
