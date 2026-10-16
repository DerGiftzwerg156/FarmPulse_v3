package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.DealerDelivery;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerDeliveryRepository extends JpaRepository<DealerDelivery, Long> {

    List<DealerDelivery> findByOrderIdOrderByIdAsc(long orderId);

    List<DealerDelivery> findBySavegameAndStatusIn(Savegame savegame, java.util.Collection<String> status);

    Optional<DealerDelivery> findByInstructionId(String instructionId);
}
