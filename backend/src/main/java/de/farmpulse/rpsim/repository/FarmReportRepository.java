package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.FarmReport;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FarmReportRepository extends JpaRepository<FarmReport, Long> {

    List<FarmReport> findBySavegameOrderByReportYearDesc(Savegame savegame);

    Optional<FarmReport> findBySavegameAndReportYear(Savegame savegame, int reportYear);
}
