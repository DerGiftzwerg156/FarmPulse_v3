package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.FarmlandOwnership;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FarmlandOwnershipRepository extends JpaRepository<FarmlandOwnership, Long> {
}
