package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.DieselTheft;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DieselTheftRepository extends JpaRepository<DieselTheft, Long> {

    List<DieselTheft> findBySavegameOrderByIdAsc(Savegame savegame);

    List<DieselTheft> findBySavegameOrderByIdDesc(Savegame savegame);
}
