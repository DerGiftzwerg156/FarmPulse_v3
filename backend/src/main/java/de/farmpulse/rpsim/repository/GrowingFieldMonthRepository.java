package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.GrowingFieldMonth;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrowingFieldMonthRepository extends JpaRepository<GrowingFieldMonth, Long> {

    Optional<GrowingFieldMonth> findBySavegameAndMonthIndexAndFarmlandId(Savegame savegame, long monthIndex, int farmlandId);

    List<GrowingFieldMonth> findBySavegameAndMonthIndexBetweenOrderByFarmlandIdAsc(Savegame savegame, long from, long to);
}
