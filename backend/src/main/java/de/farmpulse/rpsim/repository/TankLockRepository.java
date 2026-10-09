package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TankLock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TankLockRepository extends JpaRepository<TankLock, Long> {

    List<TankLock> findBySavegameOrderByIdAsc(Savegame savegame);

    boolean existsBySavegameAndVehicleId(Savegame savegame, String vehicleId);
}
