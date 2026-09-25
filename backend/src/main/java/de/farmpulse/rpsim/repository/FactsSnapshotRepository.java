package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.FactsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactsSnapshotRepository extends JpaRepository<FactsSnapshot, Long> {
}
