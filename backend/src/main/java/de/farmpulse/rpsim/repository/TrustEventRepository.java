package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.TrustEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustEventRepository extends JpaRepository<TrustEvent, Long> {

    List<TrustEvent> findByCharacterOrderByGameTimeAscIdAsc(Character character);

    List<TrustEvent> findTop20ByCharacterOrderByGameTimeDescIdDesc(Character character);

    void deleteByCharacter(Character character);
}
