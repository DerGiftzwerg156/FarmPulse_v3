package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.HusbandryRecord;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HusbandryRecordRepository extends JpaRepository<HusbandryRecord, Long> {

    List<HusbandryRecord> findBySavegame(Savegame savegame);
}
