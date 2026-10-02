package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.NpcFieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NpcFieldRecordRepository extends JpaRepository<NpcFieldRecord, Long> {

    List<NpcFieldRecord> findBySavegameOrderByFarmlandIdAsc(Savegame savegame);
}
