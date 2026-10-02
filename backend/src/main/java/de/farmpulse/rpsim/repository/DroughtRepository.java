package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Drought;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DroughtRepository extends JpaRepository<Drought, Long> {

    List<Drought> findBySavegameOrderByIdDesc(Savegame savegame);
}
