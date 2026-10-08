package de.farmpulse.rpsim.repository;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.BulkOrder;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkOrderRepository extends JpaRepository<BulkOrder, Long> {

    List<BulkOrder> findBySavegameOrderByIdDesc(Savegame savegame);

    List<BulkOrder> findBySavegameAndStatus(Savegame savegame, String status);

    List<BulkOrder> findBySavegame_IdAndStatus(Long savegameId, String status);

    Optional<BulkOrder> findByInstructionId(String instructionId);
}
