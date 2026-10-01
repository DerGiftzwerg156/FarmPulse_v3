package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.VehicleDeal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleDealRepository extends JpaRepository<VehicleDeal, Long> {

    List<VehicleDeal> findBySavegameOrderByIdDesc(Savegame savegame);

    List<VehicleDeal> findBySavegameAndStatusInOrderByIdAsc(Savegame savegame, Collection<String> statuses);
}
