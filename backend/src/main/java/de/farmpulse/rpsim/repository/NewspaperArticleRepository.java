package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.NewspaperArticle;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewspaperArticleRepository extends JpaRepository<NewspaperArticle, Long> {

    List<NewspaperArticle> findBySavegameOrderByIdAsc(Savegame savegame);

    List<NewspaperArticle> findByIssueIdOrderByPositionAsc(Long issueId);
}
