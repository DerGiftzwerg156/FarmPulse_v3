package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.NpcFieldCrop;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NpcFieldCropRepository extends JpaRepository<NpcFieldCrop, Long> {

    Optional<NpcFieldCrop> findBySavegameAndFarmlandIdAndCropYearAndFruitType(Savegame savegame, int farmlandId,
                                                                             int cropYear, String fruitType);

    List<NpcFieldCrop> findBySavegameAndFarmlandIdOrderByCropYearDescIdDesc(Savegame savegame, int farmlandId);
}
