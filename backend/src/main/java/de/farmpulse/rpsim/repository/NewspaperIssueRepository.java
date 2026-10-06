package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.NewspaperIssue;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewspaperIssueRepository extends JpaRepository<NewspaperIssue, Long> {

    List<NewspaperIssue> findBySavegameOrderByIdAsc(Savegame savegame);

    List<NewspaperIssue> findBySavegameOrderByIdDesc(Savegame savegame);

    Optional<NewspaperIssue> findFirstBySavegameOrderByIdDesc(Savegame savegame);

    boolean existsBySavegameAndMonthIndexAndMidMonth(Savegame savegame, long monthIndex, boolean midMonth);
}
