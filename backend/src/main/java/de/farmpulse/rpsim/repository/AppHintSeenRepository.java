package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.AppHintSeen;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppHintSeenRepository extends JpaRepository<AppHintSeen, String> {
}
