package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.DiaryEntry;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, Long> {

    List<DiaryEntry> findBySavegameOrderByGameTimeAscIdAsc(Savegame savegame);

    List<DiaryEntry> findTop10BySavegameOrderByGameTimeDescIdDesc(Savegame savegame);
}
