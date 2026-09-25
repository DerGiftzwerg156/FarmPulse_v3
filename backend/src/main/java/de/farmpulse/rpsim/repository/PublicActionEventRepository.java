package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.PublicActionEvent;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicActionEventRepository extends JpaRepository<PublicActionEvent, Long> {

    List<PublicActionEvent> findBySavegameOrderByGameTimeAsc(Savegame savegame);
}
