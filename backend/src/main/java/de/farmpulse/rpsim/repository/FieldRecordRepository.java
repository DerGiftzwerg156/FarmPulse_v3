package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldRecordRepository extends JpaRepository<FieldRecord, Long> {

    List<FieldRecord> findBySavegameOrderByFarmlandIdAsc(Savegame savegame);
}
