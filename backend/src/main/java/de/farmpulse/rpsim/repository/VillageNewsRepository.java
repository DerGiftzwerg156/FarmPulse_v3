package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.VillageNews;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VillageNewsRepository extends JpaRepository<VillageNews, Long> {

    List<VillageNews> findBySavegameOrderByIdAsc(Savegame savegame);

    /** Roadmap V3.1 R31-D1: the news items of an issue window (from exclusive, to inclusive). */
    List<VillageNews> findBySavegameAndGameTimeGreaterThanAndGameTimeLessThanEqualOrderByIdAsc(Savegame savegame,
                                                                                               long from, long to);
}
