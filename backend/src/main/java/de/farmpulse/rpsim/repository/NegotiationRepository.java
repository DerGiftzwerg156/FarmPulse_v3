package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.Negotiation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NegotiationRepository extends JpaRepository<Negotiation, Long> {
}
