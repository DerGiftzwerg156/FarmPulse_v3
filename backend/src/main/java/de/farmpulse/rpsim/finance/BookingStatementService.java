package de.farmpulse.rpsim.finance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.BookingLine;
import de.farmpulse.rpsim.bridge.BridgeDtos.Bookings;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.BookingEntry;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.VehicleDeal;
import de.farmpulse.rpsim.farmwork.LoanedVehicles;
import de.farmpulse.rpsim.repository.BookingEntryRepository;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.VehicleDealRepository;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking statement ("Kontoauszug", owner decisions 2026-10-06 in QUESTIONS.md): stores the single bookings of
 * {@code farm_facts.bookings} permanently, so the bank shows what was booked when - not only the month sums.
 * <ul>
 *   <li>The mod numbers its entries ({@code seq}) and keeps only the last ones; every export updates the stored entries
 *   in place (a daily sum keeps growing) and adds the new ones.</li>
 *   <li>Reload without saving: the mod's {@code nextSeq} goes back; stored entries at or after it no longer exist in
 *   the game and are deleted (like the month journal, the game state wins).</li>
 *   <li>Shop vehicle purchases ({@code SHOP_VEHICLE_BUY}) and sales ({@code SHOP_VEHICLE_SELL}) get the names of the
 *   own vehicles that appeared / disappeared in {@code assets.vehicles} since the previous export - the same rule as
 *   the investment grant (R31-B2). Exactly one waiting purchase (sale): the names belong to it. Several waiting at the
 *   same time: the names are shown on each, marked as not assignable. Nothing within
 *   {@code statement-vehicle-match-exports} exports: no name. Borrowed machines (R31-A2) and the used machines of the
 *   neighbours (R3-V, their own money type) never count.</li>
 * </ul>
 */
@Service
public class BookingStatementService {

    public static final String VEHICLE_BUY = "SHOP_VEHICLE_BUY";
    public static final String VEHICLE_SELL = "SHOP_VEHICLE_SELL";

    private final SavegameRepository savegames;
    private final FactsSnapshotRepository snapshots;
    private final BookingEntryRepository entries;
    private final VehicleDealRepository deals;
    private final FactsService facts;
    private final LoanedVehicles loaned;
    private final RpsimProperties props;

    public BookingStatementService(SavegameRepository savegames, FactsSnapshotRepository snapshots,
                                   BookingEntryRepository entries, VehicleDealRepository deals, FactsService facts,
                                   LoanedVehicles loaned, RpsimProperties props) {
        this.savegames = savegames;
        this.snapshots = snapshots;
        this.entries = entries;
        this.deals = deals;
        this.facts = facts;
        this.loaned = loaned;
        this.props = props;
    }

    @EventListener
    @Order(8)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FactsSnapshot snap = e.snapshotId() == null ? null : snapshots.findById(e.snapshotId()).orElse(null);
        if (snap == null) {
            return;
        }
        FarmFacts f = facts.parse(snap);
        if (f.bookings() == null) {
            return; // older mod: no statement
        }
        FarmFacts previous = snapshots.findFirstBySavegameAndIdLessThanOrderByIdDesc(sg, snap.getId())
                .map(facts::parse).orElse(null);
        sync(sg, f, previous);
    }

    /** Stores the entries of one export and assigns vehicle names (previous = the export before, may be null). */
    public void sync(Savegame sg, FarmFacts f, FarmFacts previous) {
        Bookings b = f.bookings();
        if (b == null || b.nextSeq() == null || b.entries() == null) {
            return;
        }
        entries.deleteAll(entries.findBySavegameAndSeqGreaterThanEqual(sg, b.nextSeq()));
        List<BookingLine> lines = b.entries().stream().filter(Objects::nonNull).filter(l -> l.seq() != null).toList();
        if (!lines.isEmpty()) {
            long from = lines.stream().mapToLong(BookingLine::seq).min().orElse(0);
            long to = lines.stream().mapToLong(BookingLine::seq).max().orElse(0);
            Map<Long, BookingEntry> stored = new HashMap<>();
            entries.findBySavegameAndSeqBetween(sg, from, to).forEach(x -> stored.put(x.getSeq(), x));
            for (BookingLine l : lines) {
                entries.save(apply(sg, stored.get(l.seq()), l));
            }
        }
        boolean rewound = previous != null && previous.gameTime() != null && f.gameTime() != null
                && previous.gameTime() > f.gameTime();
        Map<String, String> before = rewound ? null : ownVehicles(sg, previous);
        Map<String, String> now = ownVehicles(sg, f);
        Set<String> dealt = usedMachines(sg);
        List<String> appeared = before == null ? List.of() : now.entrySet().stream()
                .filter(v -> !before.containsKey(v.getKey()) && !dealt.contains(v.getKey())).map(Map.Entry::getValue)
                .toList();
        List<String> gone = before == null ? List.of() : before.entrySet().stream()
                .filter(v -> !now.containsKey(v.getKey()) && !dealt.contains(v.getKey())).map(Map.Entry::getValue)
                .toList();
        match(sg, VEHICLE_BUY, appeared);
        match(sg, VEHICLE_SELL, gone);
    }

    private BookingEntry apply(Savegame sg, BookingEntry x, BookingLine l) {
        boolean replaced = x != null && (!x.getCategory().equals(l.category())
                || x.getGameTime() != (l.gameTime() == null ? 0 : l.gameTime()));
        if (x == null) {
            x = new BookingEntry();
            x.setSavegame(sg);
            x.setSeq(l.seq());
        }
        x.setGameTime(l.gameTime() == null ? 0 : l.gameTime());
        x.setYear(l.year());
        x.setPeriod(l.period());
        x.setDayInPeriod(l.day());
        x.setCategory(l.category());
        x.setAmount(Math.round(l.amount()));
        x.setCount(l.count() == null ? 1 : l.count());
        x.setSingle(Boolean.TRUE.equals(l.single()));
        x.setLiters(l.liters() == null ? null : Math.round(l.liters()));
        x.setFillType(l.fillType());
        x.setSellPoint(l.sellPoint());
        x.setNote(l.note() == null ? null : l.note().substring(0, Math.min(255, l.note().length())));
        if (replaced) {
            // the seq was reused for another booking after a reload without saving
            x.setVehicleMatch(null);
            x.setVehicleNames(null);
            x.setMatchExports(0);
        }
        if (x.getVehicleMatch() == null && (VEHICLE_BUY.equals(l.category()) || VEHICLE_SELL.equals(l.category()))) {
            x.setVehicleMatch(BookingEntry.MATCH_PENDING);
        }
        return x;
    }

    private void match(Savegame sg, String category, List<String> names) {
        List<BookingEntry> pending = entries.findBySavegameAndVehicleMatchAndCategoryOrderBySeqAsc(sg,
                BookingEntry.MATCH_PENDING, category);
        if (pending.isEmpty()) {
            return;
        }
        if (!names.isEmpty()) {
            String joined = String.join(", ", names);
            String status = pending.size() == 1 ? BookingEntry.MATCH_MATCHED : BookingEntry.MATCH_AMBIGUOUS;
            for (BookingEntry x : pending) {
                x.setVehicleMatch(status);
                x.setVehicleNames(joined.substring(0, Math.min(1000, joined.length())));
            }
            return;
        }
        int window = props.getFormulas().getFinance().getStatementVehicleMatchExports();
        for (BookingEntry x : pending) {
            x.setMatchExports(x.getMatchExports() + 1);
            if (x.getMatchExports() >= window) {
                x.setVehicleMatch(BookingEntry.MATCH_NONE);
            }
        }
    }

    /** Own vehicles of an export (uniqueId -> name, the uniqueId when the mod exports no name); null without export. */
    Map<String, String> ownVehicles(Savegame sg, FarmFacts f) {
        if (f == null || f.assets() == null || f.assets().vehicles() == null) {
            return null;
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (BridgeDtos.Vehicle v : loaned.own(sg, f.assets().vehicles())) {
            if (v != null && v.uniqueId() != null) {
                m.put(v.uniqueId(), v.name() == null || v.name().isBlank() ? v.uniqueId() : v.name());
            }
        }
        return m;
    }

    private Set<String> usedMachines(Savegame sg) {
        return deals.findBySavegameOrderByIdDesc(sg).stream().map(VehicleDeal::getVehicleId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Entries of a game month, oldest first. */
    public List<BookingEntry> month(Savegame sg, int year, int period) {
        return entries.findBySavegameAndYearAndPeriodOrderBySeqAsc(sg, year, period);
    }

    /** Months with entries as {year, period, count}, oldest first. */
    public List<long[]> months(Savegame sg) {
        List<long[]> m = new ArrayList<>();
        for (Object[] r : entries.countPerMonth(sg)) {
            m.add(new long[] {((Number) r[0]).longValue(), ((Number) r[1]).longValue(), ((Number) r[2]).longValue()});
        }
        m.sort(java.util.Comparator.comparingLong(r -> FinanceJournalService.key((int) r[0], (int) r[1])));
        return m;
    }
}
