package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TaxYear;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxYearRepository extends JpaRepository<TaxYear, Long> {

    Optional<TaxYear> findBySavegameAndTaxYear(Savegame savegame, int taxYear);

    List<TaxYear> findBySavegameOrderByTaxYearDesc(Savegame savegame);
}
