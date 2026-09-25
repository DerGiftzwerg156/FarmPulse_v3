package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxInstructionRepository extends JpaRepository<OutboxInstruction, Long> {

    List<OutboxInstruction> findBySavegameAndStatusOrderByIdAsc(Savegame savegame, InstructionStatus status);

    List<OutboxInstruction> findBySavegameOrderByIdAsc(Savegame savegame);

    List<OutboxInstruction> findBySavegameAndTypeOrderByIdAsc(Savegame savegame, InstructionType type);

    Optional<OutboxInstruction> findByInstructionId(String instructionId);

    List<OutboxInstruction> findByBatchId(String batchId);
}
