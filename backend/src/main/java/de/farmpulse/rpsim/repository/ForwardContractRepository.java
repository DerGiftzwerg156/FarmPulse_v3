package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ForwardContractRepository extends JpaRepository<ForwardContract, Long> {

    List<ForwardContract> findBySavegameOrderByIdDesc(Savegame savegame);

    List<ForwardContract> findBySavegameAndStatus(Savegame savegame, String status);

    List<ForwardContract> findBySavegame_IdAndStatus(Long savegameId, String status);

    Optional<ForwardContract> findByInstructionId(String instructionId);
}
