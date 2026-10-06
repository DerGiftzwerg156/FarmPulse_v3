package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.CoopShareNotice;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoopShareNoticeRepository extends JpaRepository<CoopShareNotice, Long> {

    List<CoopShareNotice> findBySavegameOrderByIdAsc(Savegame savegame);
}
