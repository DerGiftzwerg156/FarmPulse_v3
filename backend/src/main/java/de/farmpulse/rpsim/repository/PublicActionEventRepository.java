package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.PublicActionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicActionEventRepository extends JpaRepository<PublicActionEvent, Long> {
}
