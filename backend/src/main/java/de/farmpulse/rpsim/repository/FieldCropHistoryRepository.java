package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldCropHistoryRepository extends JpaRepository<FieldCropHistory, Long> {

    Optional<FieldCropHistory> findBySavegameAndFarmlandIdAndCropYearAndFruitType(Savegame savegame, int farmlandId,
                                                                                    int cropYear, String fruitType);

    List<FieldCropHistory> findBySavegameAndCropYear(Savegame savegame, int cropYear);

    List<FieldCropHistory> findBySavegameAndFarmlandIdOrderByCropYearAsc(Savegame savegame, int farmlandId);
}
