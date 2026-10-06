package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FarmHolidayMonth;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FarmHolidayMonthRepository extends JpaRepository<FarmHolidayMonth, Long> {

    List<FarmHolidayMonth> findBySavegameOrderByIdAsc(Savegame savegame);

    List<FarmHolidayMonth> findBySavegameOrderByIdDesc(Savegame savegame);

    boolean existsBySavegameAndMonthIndex(Savegame savegame, long monthIndex);
}
