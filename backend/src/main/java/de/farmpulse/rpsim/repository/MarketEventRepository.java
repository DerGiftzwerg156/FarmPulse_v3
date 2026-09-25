package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.MarketEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketEventRepository extends JpaRepository<MarketEvent, Long> {
}
