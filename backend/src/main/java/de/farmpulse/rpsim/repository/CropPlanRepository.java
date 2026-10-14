package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.CropPlan;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CropPlanRepository extends JpaRepository<CropPlan, Long> {

    List<CropPlan> findBySavegameOrderByIdAsc(Savegame savegame);

    List<CropPlan> findBySavegameAndStatus(Savegame savegame, CropPlan.Status status);
}
