package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.SatisfactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SatisfactionEventRepository extends JpaRepository<SatisfactionEvent, Long> {
}
