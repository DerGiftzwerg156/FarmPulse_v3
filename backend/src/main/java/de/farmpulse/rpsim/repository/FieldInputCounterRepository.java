package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FieldInputCounter;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldInputCounterRepository extends JpaRepository<FieldInputCounter, Long> {

    List<FieldInputCounter> findBySavegame(Savegame savegame);

    boolean existsBySavegame(Savegame savegame);
}
