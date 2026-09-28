package de.farmpulse.rpsim.repository;

import java.util.Optional;

import de.farmpulse.rpsim.domain.RainPeriod;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RainPeriodRepository extends JpaRepository<RainPeriod, Long> {

    Optional<RainPeriod> findBySavegameAndMonthIndex(Savegame savegame, long monthIndex);
}
