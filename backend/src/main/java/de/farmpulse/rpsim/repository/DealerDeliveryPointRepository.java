package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.DealerDeliveryPoint;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerDeliveryPointRepository extends JpaRepository<DealerDeliveryPoint, Long> {

    List<DealerDeliveryPoint> findBySavegameOrderBySeqAsc(Savegame savegame);

    boolean existsBySavegameAndSeq(Savegame savegame, int seq);
}
