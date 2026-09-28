package de.farmpulse.rpsim.finance;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Loan;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.finance.FinanceJournalService.Month;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.CalendarText;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-B5: characters react to the real figures of the booking journal. Every complete month is evaluated
 * once, in order:
 * <ul>
 *   <li>Bank early warning: the operating result was negative for {@code early-warning-negative-months} months in a
 *   row while a bank loan runs - the advisor writes once per streak (a positive month ends the streak).</li>
 *   <li>Cooperative: the highest harvest revenue of a complete month since the start (after
 *   {@code record-min-months} months of history) - congratulation and a small trust bonus.</li>
 * </ul>
 * When several months are evaluated at once (first journal, long pause) only the latest one may trigger a message; the
 * older ones only build up the history. Without a journal nothing happens.
 */
@Service
public class FinanceNarrationService {

    private final SavegameRepository savegames;
    private final FactsService facts;
    private final FinanceJournalService journal;
    private final LoanRepository loans;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RpsimProperties props;

    public FinanceNarrationService(SavegameRepository savegames, FactsService facts, FinanceJournalService journal,
                                   LoanRepository loans, CharacterLookup lookup, NarrationRequestService narration,
                                   TrustScoreService trust, DiaryService diary, RpsimProperties props) {
        this.savegames = savegames;
        this.facts = facts;
        this.journal = journal;
        this.loans = loans;
        this.lookup = lookup;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.props = props;
    }

    private RpsimProperties.Finance cfg() {
        return props.getFormulas().getFinance();
    }

    @EventListener
    @Order(80)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        facts.latest(sg).ifPresent(f -> evaluate(sg, f));
    }

    /** Evaluates every complete month after the last evaluated one. */
    @Transactional
    public void evaluate(Savegame sg, FarmFacts f) {
        List<Month> open = journal.months(f).stream().filter(Month::complete)
                .filter(m -> sg.getFinLastMonthKey() == null || m.key() > sg.getFinLastMonthKey()).toList();
        for (int i = 0; i < open.size(); i++) {
            evaluateMonth(sg, f, open.get(i), i == open.size() - 1);
        }
    }

    private void evaluateMonth(Savegame sg, FarmFacts f, Month m, boolean mayNotify) {
        boolean consecutive = sg.getFinLastMonthKey() != null && m.key() == sg.getFinLastMonthKey() + 1;
        int seenBefore = sg.getFinMonthsSeen();
        // early warning: a gap (month without bookings) or a non-negative month ends the streak
        if (m.operatingResult() < 0) {
            sg.setFinNegativeStreak(consecutive || seenBefore == 0 ? sg.getFinNegativeStreak() + 1 : 1);
        } else {
            sg.setFinNegativeStreak(0);
            sg.setFinWarningSent(false);
        }
        if (mayNotify && cfg().isEarlyWarningEnabled() && !sg.isFinWarningSent()
                && sg.getFinNegativeStreak() >= cfg().getEarlyWarningNegativeMonths()) {
            List<Loan> active = loans.findBySavegameAndStatus(sg, LoanStatus.ACTIVE);
            if (!active.isEmpty() && warn(sg, f, active)) {
                sg.setFinWarningSent(true);
            }
        }
        // record harvest revenue
        double revenue = journal.harvestRevenue(m);
        Double record = sg.getFinRecordRevenue();
        if (mayNotify && cfg().isRecordEnabled() && seenBefore >= cfg().getRecordMinMonths() && revenue > 0
                && (record == null || revenue > record)) {
            congratulate(sg, f, m, revenue);
        }
        if (record == null || revenue > record) {
            sg.setFinRecordRevenue(revenue);
        }
        sg.setFinMonthsSeen(seenBefore + 1);
        sg.setFinLastMonthKey(m.key());
    }

    private boolean warn(Savegame sg, FarmFacts f, List<Loan> active) {
        var bank = lookup.bank(sg);
        if (bank.isEmpty()) {
            return false;
        }
        long installments = Math.round(active.stream().mapToDouble(Loan::getMonthlyInstallment).sum());
        NarrationFacts.Builder b = journal.putFacts(NarrationFacts.builder(), f)
                .put("negativeMonths", sg.getFinNegativeStreak())
                .put("monthlyInstallments", installments);
        narration.request(sg, NarrationEventType.BANK_CASHFLOW_WARNING).from(bank.get()).facts(b.build())
                .category(CommunicationCategory.CREDIT).submit();
        diary.addAuto(sg, "CREDIT", "Bank warnt vor Engpass", "Die Bank meldet sich, weil der Betrieb seit "
                + sg.getFinNegativeStreak() + " Monaten Verlust macht.", null, null);
        return true;
    }

    private void congratulate(Savegame sg, FarmFacts f, Month m, double revenue) {
        var cooperative = lookup.mandatory(sg, CharacterRole.COOPERATIVE);
        if (cooperative.isEmpty()) {
            return;
        }
        trust.recordEvent(cooperative.get(), cfg().getRecordTrustDelta(), TrustReason.RECORD_HARVEST,
                "Rekord-Ernteerlös " + CalendarText.month(m.period()));
        NarrationFacts.Builder b = journal.putFacts(NarrationFacts.builder(), f)
                .put("financeMonth", CalendarText.month(m.period()))
                .put("financeRevenue", Math.round(revenue));
        narration.request(sg, NarrationEventType.COOPERATIVE_RECORD_HARVEST).from(cooperative.get()).facts(b.build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit();
        diary.addAuto(sg, "MARKET", "Rekordmonat", "Ernteerlös von " + Math.round(revenue) + " € im "
                + CalendarText.month(m.period()) + " - so viel wie nie zuvor.", null, null);
    }
}
