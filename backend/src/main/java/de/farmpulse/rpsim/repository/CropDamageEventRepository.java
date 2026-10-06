package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CropDamageEvent;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CropDamageEventRepository extends JpaRepository<CropDamageEvent, Long> {

    List<CropDamageEvent> findBySavegameOrderByIdAsc(Savegame savegame);

    List<CropDamageEvent> findBySavegameAndCharacterOrderByIdDesc(Savegame savegame, Character character);

    List<CropDamageEvent> findBySavegameAndFarmlandIdOrderByIdDesc(Savegame savegame, int farmlandId);
}
