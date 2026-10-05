package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.AnimalDisease;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnimalDiseaseRepository extends JpaRepository<AnimalDisease, Long> {

    List<AnimalDisease> findBySavegameOrderByIdDesc(Savegame savegame);
}
