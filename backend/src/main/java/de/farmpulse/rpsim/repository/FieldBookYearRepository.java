package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.FieldBookYear;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldBookYearRepository extends JpaRepository<FieldBookYear, Long> {

    List<FieldBookYear> findBySavegameOrderByHarvestYearAsc(Savegame savegame);

    Optional<FieldBookYear> findBySavegameAndHarvestYear(Savegame savegame, int harvestYear);
}
