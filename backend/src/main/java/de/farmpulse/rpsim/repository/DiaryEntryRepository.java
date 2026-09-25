package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.DiaryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, Long> {
}
