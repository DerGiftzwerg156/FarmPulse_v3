package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.DealerOrderItem;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerOrderItemRepository extends JpaRepository<DealerOrderItem, Long> {

    List<DealerOrderItem> findByOrderIdOrderByPositionAsc(long orderId);

    List<DealerOrderItem> findByDeliveryIdOrderByPositionAsc(long deliveryId);
}
