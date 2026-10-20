package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.DealerCartItem;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DealerCartItemRepository extends JpaRepository<DealerCartItem, Long> {

    List<DealerCartItem> findBySavegameOrderByIdAsc(Savegame savegame);

    long countBySavegame(Savegame savegame);
}
