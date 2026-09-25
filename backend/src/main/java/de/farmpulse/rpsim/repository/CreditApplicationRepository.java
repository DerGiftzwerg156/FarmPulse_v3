package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.CreditApplication;
import de.farmpulse.rpsim.domain.CreditApplicationStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditApplicationRepository extends JpaRepository<CreditApplication, Long> {

    List<CreditApplication> findBySavegameOrderByIdDesc(Savegame savegame);

    List<CreditApplication> findBySavegameAndStatus(Savegame savegame, CreditApplicationStatus status);
}
