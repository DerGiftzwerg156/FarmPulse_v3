package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.Character;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRepository extends JpaRepository<Character, Long> {
}
