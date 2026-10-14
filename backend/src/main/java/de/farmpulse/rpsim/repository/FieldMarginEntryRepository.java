package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.FieldMarginEntry;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldMarginEntryRepository extends JpaRepository<FieldMarginEntry, Long> {

    List<FieldMarginEntry> findBySavegame(Savegame savegame);

    Optional<FieldMarginEntry> findBySavegameAndEntryId(Savegame savegame, long entryId);
}
