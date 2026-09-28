package de.farmpulse.rpsim.api;

import java.util.Comparator;
import java.util.List;

import de.farmpulse.rpsim.api.Views.FinanceLineView;
import de.farmpulse.rpsim.api.Views.FinanceMonthView;
import de.farmpulse.rpsim.api.Views.FinanceOverview;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties.FinanceClass;
import de.farmpulse.rpsim.finance.FinanceJournalService;
import de.farmpulse.rpsim.finance.FinanceJournalService.Month;
import de.farmpulse.rpsim.savegame.SavegameContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Roadmap V2 R2-B4: farm bookkeeping (income and expenses per game month) for the bank page. */
@RestController
public class FinanceController {

    private final SavegameContext context;
    private final FactsService facts;
    private final FinanceJournalService journal;

    public FinanceController(SavegameContext context, FactsService facts, FinanceJournalService journal) {
        this.context = context;
        this.facts = facts;
        this.journal = journal;
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
