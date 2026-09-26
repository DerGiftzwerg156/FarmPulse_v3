package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;

import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceCaseRepository extends JpaRepository<ServiceCase, Long> {

    List<ServiceCase> findBySavegameOrderByIdDesc(Savegame savegame);

    List<ServiceCase> findBySavegameAndStatusOrderByIdAsc(Savegame savegame, CaseStatus status);

    List<ServiceCase> findBySavegameAndKindInOrderByIdDesc(Savegame savegame, Collection<CaseKind> kinds);
}
