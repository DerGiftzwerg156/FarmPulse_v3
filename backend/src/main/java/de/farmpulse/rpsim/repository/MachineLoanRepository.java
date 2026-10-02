package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;

import de.farmpulse.rpsim.domain.MachineLoan;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MachineLoanRepository extends JpaRepository<MachineLoan, Long> {

    List<MachineLoan> findBySavegameOrderByIdDesc(Savegame savegame);

    List<MachineLoan> findBySavegameAndStatusInOrderByIdAsc(Savegame savegame, Collection<String> statuses);
}
