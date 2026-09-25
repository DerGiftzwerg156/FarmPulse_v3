package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.TrustEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustEventRepository extends JpaRepository<TrustEvent, Long> {
}
