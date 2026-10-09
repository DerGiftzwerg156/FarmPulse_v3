package de.farmpulse.rpsim.repository;

import java.util.List;

import de.farmpulse.rpsim.domain.BookingEntry;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingEntryRepository extends JpaRepository<BookingEntry, Long> {

    List<BookingEntry> findBySavegameAndSeqBetween(Savegame savegame, long from, long to);

    /** Entries the game no longer has after a reload without saving (seq at or after the mod's nextSeq). */
    List<BookingEntry> findBySavegameAndSeqGreaterThanEqual(Savegame savegame, long seq);

    List<BookingEntry> findBySavegameAndVehicleMatchAndCategoryOrderBySeqAsc(Savegame savegame, String vehicleMatch,
                                                                             String category);

    List<BookingEntry> findBySavegameAndYearAndPeriodOrderBySeqAsc(Savegame savegame, int year, int period);

    /** Months with entries: [year, period, count]. */
    @Query("select e.year, e.period, count(e) from BookingEntry e where e.savegame = :sg group by e.year, e.period")
    List<Object[]> countPerMonth(@Param("sg") Savegame savegame);

    boolean existsBySavegame(Savegame savegame);
}
