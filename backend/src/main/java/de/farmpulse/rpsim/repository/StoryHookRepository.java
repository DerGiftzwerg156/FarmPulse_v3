package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.StoryHook;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryHookRepository extends JpaRepository<StoryHook, Long> {
}
