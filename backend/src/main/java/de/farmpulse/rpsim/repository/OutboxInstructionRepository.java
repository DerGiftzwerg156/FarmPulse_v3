package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.OutboxInstruction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxInstructionRepository extends JpaRepository<OutboxInstruction, Long> {
}
