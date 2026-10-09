package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.NightWorkSample;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NightWorkSampleRepository extends JpaRepository<NightWorkSample, Long> {

    List<NightWorkSample> findBySavegameOrderByIdAsc(Savegame savegame);

    /** Roadmap V3.1 R31-D4: night milliseconds of helpers in (from, to]. */
    @Query("select coalesce(sum(s.nightMs), 0) from NightWorkSample s where s.savegame = :sg and s.gameTime > :from "
            + "and s.gameTime <= :to")
    long sumNightMs(@Param("sg") Savegame sg, @Param("from") long from, @Param("to") long to);
}
