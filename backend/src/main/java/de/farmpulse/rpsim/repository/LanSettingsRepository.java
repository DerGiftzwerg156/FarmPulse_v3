package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.LanSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LanSettingsRepository extends JpaRepository<LanSettings, Long> {
}
