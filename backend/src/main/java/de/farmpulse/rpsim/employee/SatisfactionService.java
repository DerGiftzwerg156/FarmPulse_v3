package de.farmpulse.rpsim.employee;

import static de.farmpulse.rpsim.common.Formulas.clamp;

import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.SatisfactionCategory;
import de.farmpulse.rpsim.domain.SatisfactionEvent;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.SatisfactionEventRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee satisfaction: three event-driven categories with natural decay plus the live reading
 * workingConditions (vehicle condition from the facts export). Drives the monthly EMPLOYEE_EFFECT money
 * instruction and the two-step resignation escalation (warning after 14, resignation after 30 days).
 */
@Service
public class SatisfactionService {

    public static final String RELATED = "EMPLOYEE";

    private final EmployeeRepository employees;
    private final SatisfactionEventRepository events;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RpsimProperties props;

    public SatisfactionService(EmployeeRepository employees, SatisfactionEventRepository events,
                               SavegameRepository savegames, FactsService facts, OutboxService outbox,
                               NarrationRequestService narration, DiaryService diary, RpsimProperties props) {
        this.employees = employees;
        this.events = events;
        this.savegames = savegames;
        this.facts = facts;
        this.outbox = outbox;
        this.narration = narration;
        this.diary = diary;
        this.props = props;
    }

    private RpsimProperties.Satisfaction cfg() {
        return props.getFormulas().getSatisfaction();
    }

    /** Current view of an employee's needs (decay applied, working conditions read live). */
    public record Needs(double payFairness, double workload, double appreciation, double workingConditions, double score,
                        double effectMultiplier, double effectiveSkill) {
    }

    /** Applies natural decay up to "now" and persists it. */
    public void applyDecay(Employee e, long now) {
        double days = GameTime.toDays(now - e.getNeedsUpdatedAtGameTime());
        if (days <= 0) {
            return;
        }
        e.setPayFairness(SatisfactionFormula.decay(e.getPayFairness(), cfg().getPayFairnessDecayPerDay(), days, cfg()));
        e.setWorkload(SatisfactionFormula.decay(e.getWorkload(), cfg().getWorkloadDecayPerDay(), days, cfg()));
        e.setAppreciation(SatisfactionFormula.decay(e.getAppreciation(), cfg().getAppreciationDecayPerDay(), days, cfg()));
        e.setNeedsUpdatedAtGameTime(now);
    }

    public double workingConditions(Savegame sg) {
        return facts.latest(sg).map(f -> FactsService.averageCondition(f, cfg().getStartValue())).orElse(cfg().getStartValue());
    }

    public Needs needs(Employee e) {
        long now = e.getSavegame().getCurrentGameTime();
        double days = Math.max(0, GameTime.toDays(now - e.getNeedsUpdatedAtGameTime()));
        double pf = SatisfactionFormula.decay(e.getPayFairness(), cfg().getPayFairnessDecayPerDay(), days, cfg());
        double wl = SatisfactionFormula.decay(e.getWorkload(), cfg().getWorkloadDecayPerDay(), days, cfg());
        double ap = SatisfactionFormula.decay(e.getAppreciation(), cfg().getAppreciationDecayPerDay(), days, cfg());
        double wc = workingConditions(e.getSavegame());
        double score = SatisfactionFormula.score(pf, wl, ap, wc, cfg());
        double mult = SatisfactionFormula.effectMultiplier(score, cfg());
        return new Needs(pf, wl, ap, wc, score, mult, SatisfactionFormula.effectiveSkill(e.getSkill(), mult));
    }

    @Transactional
    public SatisfactionEvent record(Employee e, SatisfactionCategory category, double delta, String reason) {
        long now = e.getSavegame().getCurrentGameTime();
        applyDecay(e, now);
        switch (category) {
            case PAY_FAIRNESS -> e.setPayFairness(clamp(e.getPayFairness() + delta, 0, cfg().getCategoryMax()));
            case WORKLOAD -> e.setWorkload(clamp(e.getWorkload() + delta, 0, cfg().getCategoryMax()));
            case APPRECIATION -> e.setAppreciation(clamp(e.getAppreciation() + delta, 0, cfg().getCategoryMax()));
            case WORKING_CONDITIONS -> throw new IllegalArgumentException("workingConditions is a live reading");
        }
        SatisfactionEvent ev = new SatisfactionEvent();
        ev.setSavegame(e.getSavegame());
        ev.setEmployee(e);
        ev.setGameTime(now);
        ev.setCategory(category);
        ev.setDelta(delta);
        ev.setReason(reason);
        return events.save(ev);
    }

    /** Raise: payFairness + points per percent (capped). */
    @Transactional
    public void raise(Employee e, long newSalary) {
        if (newSalary <= e.getMonthlySalary()) {
            throw new de.farmpulse.rpsim.common.BusinessRuleException("NO_RAISE", "Das neue Gehalt muss höher sein.");
        }
        double pct = (newSalary - e.getMonthlySalary()) * 100.0 / e.getMonthlySalary();
        double points = Math.min(cfg().getRaiseMaxPoints(), pct * cfg().getRaisePointsPerPercent());
        e.setMonthlySalary(newSalary);
        record(e, SatisfactionCategory.PAY_FAIRNESS, points, "Gehaltserhöhung +" + Math.round(pct) + " %");
        thanks(e, "RAISE");
    }

    /** Granted days off: workload relief. */
    @Transactional
    public void timeOff(Employee e, int days) {
        if (days <= 0) {
            throw new de.farmpulse.rpsim.common.BusinessRuleException("INVALID_DAYS", "Mindestens ein freier Tag.");
        }
        record(e, SatisfactionCategory.WORKLOAD, days * cfg().getTimeOffPointsPerDay(), days + " freie Tage");
        e.setTimeOffUntilGameTime(e.getSavegame().getCurrentGameTime() + GameTime.days(days));
        thanks(e, "TIME_OFF");
    }

    /** Active mail/conversation with the employee: appreciation (with cooldown against farming). */
    @Transactional
    public boolean conversation(Employee e) {
        long now = e.getSavegame().getCurrentGameTime();
        if (e.getLastConversationGameTime() != null
                && GameTime.toDays(now - e.getLastConversationGameTime()) < cfg().getConversationCooldownDays()) {
            return false;
        }
        e.setLastConversationGameTime(now);
        record(e, SatisfactionCategory.APPRECIATION, cfg().getConversationPoints(), "Gespräch");
        return true;
    }

    /** Salary delay (system coupling): negative payFairness event. */
    @Transactional
    public void salaryOverdue(Employee e) {
        record(e, SatisfactionCategory.PAY_FAIRNESS, cfg().getSalaryOverdueDelta(), "Gehaltsverzug");
        narration.request(e.getSavegame(), NarrationEventType.EMPLOYEE_SALARY_OVERDUE).from(e.getCharacter())
                .facts(NarrationFacts.builder().put("salary", e.getMonthlySalary()).build())
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, e.getId()).submit();
    }

    private void thanks(Employee e, String reason) {
        narration.request(e.getSavegame(), NarrationEventType.EMPLOYEE_THANKS).from(e.getCharacter())
                .facts(NarrationFacts.builder().put("reason", reason).put("salary", e.getMonthlySalary()).build())
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, e.getId()).submit();
    }

    /** Daily: decay + resignation escalation (warning >= 14 days below 30 points, resignation >= 30 days). */
    @EventListener
    @Transactional
    public void onDay(GameDayPassedEvent ev) {
        Savegame sg = savegames.findById(ev.savegameId()).orElseThrow();
        for (Employee e : employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE)) {
            checkEscalation(e);
        }
    }

    @Transactional
    public void checkEscalation(Employee e) {
        Savegame sg = e.getSavegame();
        long now = sg.getCurrentGameTime();
        applyDecay(e, now);
        Needs n = needs(e);
        if (n.score() >= cfg().getWarningThreshold()) {
            e.setLowSatisfactionSinceGameTime(null);
            e.setWarningSent(false);
            return;
        }
        if (e.getLowSatisfactionSinceGameTime() == null) {
            e.setLowSatisfactionSinceGameTime(now);
        }
        double lowDays = GameTime.toDays(now - e.getLowSatisfactionSinceGameTime());
        if (lowDays >= cfg().getTerminationAfterDays()) {
            e.setStatus(EmployeeStatus.TERMINATED);
            e.setTerminatedAtGameTime(now);
            e.getCharacter().setStatus(CharacterStatus.TERMINATED);
            e.getCharacter().setTerminationReason(TerminationReason.RESIGNED);
            e.getCharacter().setLeftAtGameTime(now);
            narration.request(sg, NarrationEventType.EMPLOYEE_RESIGNATION).from(e.getCharacter())
                    .facts(NarrationFacts.builder().put("weakestNeed", weakest(n)).build())
                    .category(CommunicationCategory.EMPLOYEE).related(RELATED, e.getId()).submit();
            diary.addAuto(sg, "EMPLOYEE", e.getCharacter().getName() + " hat gekündigt",
                    "Nach langer Unzufriedenheit hat " + e.getCharacter().getName() + " den Hof verlassen.", RELATED, e.getId());
        } else if (lowDays >= cfg().getWarningAfterDays() && !e.isWarningSent()) {
            e.setWarningSent(true);
            narration.request(sg, NarrationEventType.EMPLOYEE_WARNING).from(e.getCharacter())
                    .facts(NarrationFacts.builder().put("weakestNeed", weakest(n)).build())
                    .category(CommunicationCategory.EMPLOYEE).related(RELATED, e.getId()).submit();
        }
    }

    static SatisfactionCategory weakest(Needs n) {
        double min = Math.min(Math.min(n.payFairness(), n.workload()), Math.min(n.appreciation(), n.workingConditions()));
        if (min == n.payFairness()) return SatisfactionCategory.PAY_FAIRNESS;
        if (min == n.workload()) return SatisfactionCategory.WORKLOAD;
        if (min == n.appreciation()) return SatisfactionCategory.APPRECIATION;
        return SatisfactionCategory.WORKING_CONDITIONS;
    }

    /** Monthly EMPLOYEE_EFFECT money instruction (purely economic tool effect, no FS parameter). */
    @EventListener
    @Transactional
    public void onMonth(GameMonthPassedEvent ev) {
        Savegame sg = savegames.findById(ev.savegameId()).orElseThrow();
        for (Employee e : employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE)) {
            bookMonthlyEffect(e);
        }
    }

    @Transactional
    public long bookMonthlyEffect(Employee e) {
        Needs n = needs(e);
        e.setLastEffectMultiplier(n.effectMultiplier());
        long amount = SatisfactionFormula.effectAmount(n.effectMultiplier(), cfg());
        if (amount != 0) {
            outbox.money(e.getSavegame(), amount, MoneyReason.EMPLOYEE_EFFECT, "Arbeitsleistung " + e.getCharacter().getName(),
                    new Related(RELATED, e.getId()));
        }
        return amount;
    }
}
