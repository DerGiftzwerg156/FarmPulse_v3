package de.farmpulse.rpsim.api;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.api.Views.FinanceLineView;
import de.farmpulse.rpsim.api.Views.FinanceMonthView;
import de.farmpulse.rpsim.api.Views.FinanceOverview;
import de.farmpulse.rpsim.api.Views.StatementEntryView;
import de.farmpulse.rpsim.api.Views.StatementMonthView;
import de.farmpulse.rpsim.api.Views.StatementView;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties.FinanceClass;
import de.farmpulse.rpsim.domain.BookingEntry;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.finance.BookingStatementService;
import de.farmpulse.rpsim.finance.FinanceJournalService;
import de.farmpulse.rpsim.finance.FinanceJournalService.Month;
import de.farmpulse.rpsim.savegame.SavegameContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Roadmap V2 R2-B4: farm bookkeeping (income and expenses per game month) for the bank page. */
@RestController
public class FinanceController {

    private final SavegameContext context;
    private final FactsService facts;
    private final FinanceJournalService journal;
    private final BookingStatementService statement;

    public FinanceController(SavegameContext context, FactsService facts, FinanceJournalService journal,
                             BookingStatementService statement) {
        this.context = context;
        this.facts = facts;
        this.journal = journal;
        this.statement = statement;
    }

    @GetMapping("/api/finances")
    @Transactional(readOnly = true)
    public FinanceOverview finances() {
        FarmFacts f = facts.latest(context.requireActive()).orElse(null);
        if (!journal.hasJournal(f)) {
            return new FinanceOverview(false, List.of());
        }
        return new FinanceOverview(true, journal.months(f).stream().map(FinanceController::view).toList());
    }

    /**
     * Booking statement ("Kontoauszug"): the entries of a game month - the requested one, else the latest month with
     * entries. {@code available} = the latest export carries the statement block (current mod).
     */
    @GetMapping("/api/finances/statement")
    @Transactional(readOnly = true)
    public StatementView statement(@RequestParam(required = false) Integer year,
                                   @RequestParam(required = false) Integer period) {
        Savegame sg = context.requireActive();
        FarmFacts f = facts.latest(sg).orElse(null);
        List<StatementMonthView> months = statement.months(sg).stream()
                .map(m -> new StatementMonthView((int) m[0], (int) m[1], m[2])).toList();
        boolean available = f != null && f.bookings() != null;
        if (months.isEmpty()) {
            return new StatementView(available, null, null, months, List.of());
        }
        StatementMonthView shown = months.stream().filter(m -> year != null && period != null && m.year() == year
                && m.period() == period).findFirst().orElse(months.getLast());
        Map<String, String> sellPoints = new HashMap<>();
        facts.marketContext(sg).map(c -> c.sellPoints() == null ? List.<de.farmpulse.rpsim.bridge.BridgeDtos.SellPoint>of()
                : c.sellPoints()).orElse(List.of()).forEach(p -> {
                    if (p != null && p.id() != null && p.name() != null) {
                        sellPoints.put(p.id(), p.name());
                    }
                });
        List<StatementEntryView> entries = statement.month(sg, shown.year(), shown.period()).stream()
                .map(x -> entry(x, journal.classify(x.getCategory(), x.getAmount()).name(),
                        x.getSellPoint() == null ? null : sellPoints.get(x.getSellPoint())))
                .toList();
        return new StatementView(available, shown.year(), shown.period(), months, entries);
    }

    static StatementEntryView entry(BookingEntry x, String financeClass, String sellPointName) {
        return new StatementEntryView(x.getSeq(), x.getGameTime(), x.getYear(), x.getPeriod(), x.getDayInPeriod(),
                x.getCategory(), financeClass, x.getAmount(), x.getCount(), x.isSingle(), x.getLiters(), x.getFillType(),
                x.getSellPoint(), sellPointName, x.getNote(), x.getVehicleMatch(), x.getVehicleNames());
    }

    static FinanceMonthView view(Month m) {
        return new FinanceMonthView(m.year(), m.period(), m.complete(), Math.round(m.sum(FinanceClass.OPERATING_INCOME)),
                Math.round(m.sum(FinanceClass.OPERATING_EXPENSE)), Math.round(m.operatingResult()),
                Math.round(m.sum(FinanceClass.INVESTMENT)), Math.round(m.sum(FinanceClass.DIVESTMENT)),
                Math.round(m.sum(FinanceClass.FINANCING)), Math.round(m.sum(FinanceClass.IGNORE)),
                m.lines().stream().sorted(Comparator.comparing(FinanceJournalService.Line::category))
                        .map(l -> new FinanceLineView(l.category(), Math.round(l.amount()), l.financeClass().name()))
                        .toList());
    }
}
