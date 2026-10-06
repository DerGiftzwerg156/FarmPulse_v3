package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.CoopPriceYear;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoopPriceYearRepository extends JpaRepository<CoopPriceYear, Long> {

    List<CoopPriceYear> findBySavegameOrderByIdAsc(Savegame savegame);

    List<CoopPriceYear> findBySavegameAndCropYear(Savegame savegame, int cropYear);

    Optional<CoopPriceYear> findBySavegameAndCropYearAndFillType(Savegame savegame, int cropYear, String fillType);
}
