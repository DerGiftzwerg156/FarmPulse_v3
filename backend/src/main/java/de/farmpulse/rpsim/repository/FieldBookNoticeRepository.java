package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.FieldBookNotice;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldBookNoticeRepository extends JpaRepository<FieldBookNotice, Long> {

    List<FieldBookNotice> findBySavegameOrderByIdAsc(Savegame savegame);
}
