package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustEventRepository extends JpaRepository<TrustEvent, Long> {

    List<TrustEvent> findByCharacterOrderByGameTimeAscIdAsc(Character character);

    List<TrustEvent> findTop20ByCharacterOrderByGameTimeDescIdDesc(Character character);

    void deleteByCharacter(Character character);

    /** Roadmap V3 R3-T1: whether a trust event of this reason was ever recorded in the savegame. */
    boolean existsBySavegameAndReason(Savegame savegame, TrustReason reason);
}
