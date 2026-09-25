package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunicationRepository extends JpaRepository<Communication, Long> {

    List<Communication> findBySavegameAndChannelOrderByGameTimeDescIdDesc(Savegame savegame, Channel channel);

    List<Communication> findBySavegameAndChannelAndCallStatus(Savegame savegame, Channel channel, CallStatus status);

    List<Communication> findBySavegameOrderByIdAsc(Savegame savegame);

    List<Communication> findByThreadRootIdOrderByIdAsc(Long threadRootId);

    List<Communication> findBySavegameAndCharacterOrderByIdDesc(Savegame savegame, Character character);

    long countBySavegameAndChannelAndReadFlagFalse(Savegame savegame, Channel channel);
}
