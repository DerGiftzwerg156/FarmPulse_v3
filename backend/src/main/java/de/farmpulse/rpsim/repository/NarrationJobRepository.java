package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.NarrationJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NarrationJobRepository extends JpaRepository<NarrationJob, Long> {
}
