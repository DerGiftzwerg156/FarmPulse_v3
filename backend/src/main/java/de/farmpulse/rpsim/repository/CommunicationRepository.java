package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.Communication;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunicationRepository extends JpaRepository<Communication, Long> {
}
