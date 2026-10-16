package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.DealerOrder;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerOrderRepository extends JpaRepository<DealerOrder, Long> {

    List<DealerOrder> findBySavegameOrderByIdDesc(Savegame savegame);

    List<DealerOrder> findBySavegameAndStatusIn(Savegame savegame, java.util.Collection<String> status);

    Optional<DealerOrder> findByNegotiationId(Long negotiationId);
}
