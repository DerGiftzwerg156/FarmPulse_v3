package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;

import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, Long> {

    List<Contract> findBySavegameOrderByIdDesc(Savegame savegame);

    List<Contract> findBySavegameAndStatusOrderByIdAsc(Savegame savegame, ContractStatus status);

    List<Contract> findBySavegameAndKindAndStatusInOrderByIdAsc(Savegame savegame, ContractKind kind,
                                                                Collection<ContractStatus> status);

    List<Contract> findBySavegame_IdAndStatus(Long savegameId, ContractStatus status);
}
