package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;

import de.farmpulse.rpsim.domain.InvestorContract;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorContractRepository extends JpaRepository<InvestorContract, Long> {

    List<InvestorContract> findBySavegameOrderByIdDesc(Savegame savegame);

    List<InvestorContract> findBySavegameAndStatusOrderByIdAsc(Savegame savegame, String status);

    List<InvestorContract> findBySavegameAndStatusInOrderByIdAsc(Savegame savegame, Collection<String> statuses);

    List<InvestorContract> findByCaseIdOrderByPackageNoAsc(Long caseId);
}
