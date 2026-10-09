package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldBookEntryRepository extends JpaRepository<FieldBookEntry, Long> {

    List<FieldBookEntry> findBySavegameOrderByIdAsc(Savegame savegame);

    List<FieldBookEntry> findBySavegameAndStatus(Savegame savegame, FieldBookEntry.Status status);

    List<FieldBookEntry> findBySavegameAndFarmlandIdOrderByIdAsc(Savegame savegame, int farmlandId);

    List<FieldBookEntry> findBySavegameAndHarvestYear(Savegame savegame, int harvestYear);

    boolean existsBySavegameAndFarmlandId(Savegame savegame, int farmlandId);
}
