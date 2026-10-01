package de.farmpulse.rpsim.diary;

import java.util.List;
import java.util.Locale;

import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.DiaryEntry;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.LoanStatus;
import de.farmpulse.rpsim.domain.Milestone;
import de.farmpulse.rpsim.domain.MilestoneKey;
import de.farmpulse.rpsim.domain.PaymentDelay;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.LoanRepository;
import de.farmpulse.rpsim.repository.MilestoneRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-T1: the fixed list of milestones (owner decisions in QUESTIONS.md). A reached milestone is stored once,
 * written to the diary as {@code MILESTONE} entry and shown as a badge on the start screen. No mechanical effect.
 * <ul>
 *   <li>Every game day the milestones that follow from stored data are checked - also for running savegames after the
 *       update (owner decision): a repaid bank loan, the area of the farm's fields, the cooperative's congratulation on
 *       a record harvest (trust event), a completed goods trade with a neighbour (trust event) and the crop-rotation
 *       streak kept by the authority.</li>
 *   <li>At the year change the finished FS25 year is checked for payment delays. It counts only when it was observed
 *       from its start, so the first year after the start or the update never counts.</li>
 * </ul>
 */
@Service
public class MilestoneService {

    private final SavegameRepository savegames;
    private final MilestoneRepository milestones;
    private final LoanRepository loans;
    private final TrustEventRepository trustEvents;
    private final ContractRepository contracts;
    private final EmployeeRepository employees;
    private final ServiceCaseRepository cases;
    private final FieldService fields;
    private final PaymentDelayService delays;
    private final DiaryService diary;
    private final FactsService facts;
    private final GameTime gameTime;
    private final RpsimProperties props;

    public MilestoneService(SavegameRepository savegames, MilestoneRepository milestones, LoanRepository loans,
                            TrustEventRepository trustEvents, ContractRepository contracts, EmployeeRepository employees,
                            ServiceCaseRepository cases, FieldService fields, PaymentDelayService delays,
                            DiaryService diary, FactsService facts, GameTime gameTime, RpsimProperties props) {
        this.savegames = savegames;
        this.milestones = milestones;
        this.loans = loans;
        this.trustEvents = trustEvents;
        this.contracts = contracts;
        this.employees = employees;
        this.cases = cases;
        this.fields = fields;
        this.delays = delays;
        this.diary = diary;
        this.facts = facts;
        this.gameTime = gameTime;
        this.props = props;
    }

    private RpsimProperties.Milestones cfg() {
        return props.getFormulas().getMilestones();
    }

    public List<Milestone> list(Savegame sg) {
        return milestones.findBySavegameOrderByReachedGameTimeAscIdAsc(sg);
    }

    /** Daily, after the day's other work. */
    @EventListener
    @Order(95)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        watch(sg);
        check(sg);
    }

    /** Payment delays count from the first check on (owner decision: no payment-delay year before the update). */
    private void watch(Savegame sg) {
        if (sg.getMilestoneWatchFrom() == null) {
            sg.setMilestoneWatchFrom(sg.getCurrentGameTime());
        }
    }

    @Transactional
    public void check(Savegame sg) {
        RpsimProperties.Milestones c = cfg();
        if (c.isLoanRepaidEnabled() && loans.existsBySavegameAndStatus(sg, LoanStatus.PAID_OFF)) {
            reach(sg, MilestoneKey.LOAN_REPAID, "Erster Kredit getilgt",
                    "Der erste Bankkredit ist vollständig zurückgezahlt.");
        }
        if (c.isAreaEnabled() && !milestones.existsBySavegameAndMilestoneKey(sg, MilestoneKey.AREA)) {
            double hectares = fields.records(sg).stream()
                    .mapToDouble((FieldRecord r) -> r.getHectares() == null ? 0 : r.getHectares()).sum();
            if (hectares >= c.getAreaHectares()) {
                reach(sg, MilestoneKey.AREA, number(c.getAreaHectares()) + " ha bewirtschaftet",
                        "Der Hof bewirtschaftet jetzt " + number(hectares) + " ha.");
            }
        }
        if (c.isRecordHarvestEnabled() && trustEvents.existsBySavegameAndReason(sg, TrustReason.RECORD_HARVEST)) {
            reach(sg, MilestoneKey.RECORD_HARVEST, "Rekordernte",
                    "Die Genossenschaft gratuliert zum höchsten Ernteerlös eines Monats seit Spielbeginn.");
        }
        if (c.isCropRotationEnabled() && sg.getRotationCleanYears() >= c.getCropRotationYears()) {
            reach(sg, MilestoneKey.CROP_ROTATION, c.getCropRotationYears() + " Jahre Fruchtfolge ohne Beanstandung",
                    "Seit " + c.getCropRotationYears() + " Erntejahren in Folge hat das Amt keine Fruchtfolge beanstandet.");
        }
        if (c.isNeighborTradeEnabled() && trustEvents.existsBySavegameAndReason(sg, TrustReason.NEIGHBOR_TRADE)) {
            reach(sg, MilestoneKey.NEIGHBOR_TRADE, "Erster Handel mit einem Nachbarn",
                    "Zum ersten Mal wurde Ware mit einem Nachbarn gehandelt.");
        }
    }

    /**
     * Year change (period 12 -> 1): the finished year counts when it was observed from its start and had no payment
     * delay. Runs before the month's other listeners; a delay still open now counts for the new year too.
     */
    @EventListener
    @Order(1)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (gameTime.periodOfYear(sg, e.gameTime()) != 1) {
            return;
        }
        long month = gameTime.monthIndex(sg, e.gameTime());
        long yearStart = gameTime.monthStart(sg, month);
        long previousStart = gameTime.monthStart(sg, month - 12);
        Long watchFrom = sg.getMilestoneWatchFrom();
        if (cfg().isYearWithoutDelayEnabled() && watchFrom != null && watchFrom <= previousStart
                && !delays.any(sg, previousStart, yearStart)) {
            Integer year = facts.latest(sg).map(f -> f.calendar() == null || f.calendar().year() == null
                    ? null : f.calendar().year() - 1).orElse(null);
            reach(sg, MilestoneKey.YEAR_WITHOUT_DELAY, "Ein Jahr ohne Zahlungsverzug",
                    (year == null ? "Ein ganzes Jahr" : "Im Jahr " + year)
                            + " wurden alle Raten, Steuern, Vertragszahlungen und Gehälter pünktlich bezahlt.");
        }
        if (openDelay(sg)) {
            delays.record(sg, PaymentDelay.OPEN_AT_YEAR_START, yearStart);
        }
        watch(sg);
    }

    /** A delay that is still open: a loan in arrears, an overdue contract payment or salary, an overdue tax bill. */
    boolean openDelay(Savegame sg) {
        long now = sg.getCurrentGameTime();
        return loans.findBySavegameOrderByIdAsc(sg).stream()
                .anyMatch(l -> l.getStatus() != LoanStatus.PAID_OFF && l.getOverdueSinceGameTime() != null)
                || contracts.findBySavegameAndStatusOrderByIdAsc(sg, ContractStatus.ACTIVE).stream()
                        .anyMatch(c -> c.isPaymentOverdue())
                || employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE).stream()
                        .anyMatch(emp -> emp.isSalaryOverdue())
                || cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.TAX_BILL)).stream()
                        .anyMatch(b -> b.getStatus() == CaseStatus.AWAITING_PLAYER && b.getDeadlineGameTime() != null
                                && b.getDeadlineGameTime() < now);
    }

    /** Stores a milestone once and writes its diary entry; false when it was reached before. */
    boolean reach(Savegame sg, MilestoneKey key, String title, String text) {
        if (milestones.existsBySavegameAndMilestoneKey(sg, key)) {
            return false;
        }
        Milestone m = new Milestone();
        m.setSavegame(sg);
        m.setMilestoneKey(key);
        m.setReachedGameTime(sg.getCurrentGameTime());
        milestones.save(m);
        DiaryEntry entry = diary.addMilestone(sg, title, text, m.getId());
        m.setDiaryEntryId(entry.getId());
        return true;
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.GERMANY, "%.1f", v);
    }
}
