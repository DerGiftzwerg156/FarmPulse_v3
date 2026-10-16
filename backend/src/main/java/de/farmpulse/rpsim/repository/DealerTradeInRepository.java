package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.DealerTradeIn;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerTradeInRepository extends JpaRepository<DealerTradeIn, Long> {

    List<DealerTradeIn> findByOrderIdOrderByIdAsc(long orderId);

    List<DealerTradeIn> findBySavegameAndStatus(Savegame savegame, String status);
}
