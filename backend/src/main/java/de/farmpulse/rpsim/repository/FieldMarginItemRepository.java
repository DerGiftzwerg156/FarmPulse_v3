package de.farmpulse.rpsim.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.domain.FieldMarginItem;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldMarginItemRepository extends JpaRepository<FieldMarginItem, Long> {

    List<FieldMarginItem> findBySavegameOrderByIdAsc(Savegame savegame);

    List<FieldMarginItem> findBySavegameAndEntryIdOrderByIdAsc(Savegame savegame, long entryId);

    List<FieldMarginItem> findBySavegameAndEntryIdIn(Savegame savegame, Collection<Long> entryIds);

    Optional<FieldMarginItem> findFirstBySavegameAndEntryIdAndKindAndReference(Savegame savegame, long entryId,
                                                                               FieldMarginItem.Kind kind, String reference);

    boolean existsBySavegameAndReference(Savegame savegame, String reference);
}
