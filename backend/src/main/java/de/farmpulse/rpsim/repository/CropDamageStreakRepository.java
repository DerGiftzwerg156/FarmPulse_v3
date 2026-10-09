package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.CropDamageStreak;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CropDamageStreakRepository extends JpaRepository<CropDamageStreak, Long> {

    List<CropDamageStreak> findBySavegameOrderByIdAsc(Savegame savegame);
}
