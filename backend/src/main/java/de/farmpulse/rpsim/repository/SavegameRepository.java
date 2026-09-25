package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavegameRepository extends JpaRepository<Savegame, Long> {
}
