package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;

import de.farmpulse.rpsim.domain.BridgeRewind;
import de.farmpulse.rpsim.domain.RewindStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BridgeRewindRepository extends JpaRepository<BridgeRewind, Long> {

    List<BridgeRewind> findBySavegameAndStatusInOrderByIdAsc(Savegame savegame, Collection<RewindStatus> status);

    List<BridgeRewind> findBySavegameOrderByIdAsc(Savegame savegame);
}
