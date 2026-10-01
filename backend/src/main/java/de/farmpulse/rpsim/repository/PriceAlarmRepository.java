package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.PriceAlarm;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceAlarmRepository extends JpaRepository<PriceAlarm, Long> {

    List<PriceAlarm> findBySavegameOrderByIdDesc(Savegame savegame);

    List<PriceAlarm> findBySavegameAndStatus(Savegame savegame, String status);
}
