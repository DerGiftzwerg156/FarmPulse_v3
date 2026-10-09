package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FieldBookCounter;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldBookCounterRepository extends JpaRepository<FieldBookCounter, Long> {

    List<FieldBookCounter> findBySavegame(Savegame savegame);
}
