package de.farmpulse.rpsim.employee;

import java.util.Optional;

import de.farmpulse.rpsim.authority.BurdeningEvents;
import de.farmpulse.rpsim.authority.BurdeningEvents.Burden;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.SatisfactionCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-B5: sickness and work accidents (owner decisions 2026-10-05 in QUESTIONS.md).
 * <ul>
 *   <li>Daily per active employee (switch SICK_LEAVE, idyllic factor): sickness with sickness-probability-per-day,
 *   an accident (accident-roles only) with accident-probability-per-day x risk factor - x risk-bad-factor for each of
 *   the needs workload (hours of R2-A4) and working conditions (vehicle condition) below risk-bad-threshold, x
 *   risk-good-factor when both are from risk-good-threshold. Not during a training or time off, at most one absence at
 *   a time.</li>
 *   <li>The employee is away for sickness-days / accident-days (ON_LEAVE in EMPLOYEE_ROSTER, no helper); the salary
 *   continues. The office clerk - or the employee - reports it; the Berufsgenossenschaft reports an accident.</li>
 *   <li>Get-well wishes once per absence: appreciation and trust, a thank-you mail; diary at absence and return.</li>
 * </ul>
 */
@Service
public class SickLeaveService {

    public static final String SICKNESS = "SICKNESS";
    public static final String ACCIDENT = "ACCIDENT";
    public static final String RELATED = SatisfactionService.RELATED;

    private final SavegameRepository savegames;
    private final EmployeeRepository employees;
    private final SatisfactionService satisfaction;
    private final BurdeningEvents burden;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RandomSource random;
    private final ApplicationEventPublisher publisher;
    private final RpsimProperties props;

    public SickLeaveService(SavegameRepository savegames, EmployeeRepository employees, SatisfactionService satisfaction,
                            BurdeningEvents burden, ServiceRoleService roles, NarrationRequestService narration,
                            TrustScoreService trust, DiaryService diary, RandomSource random,
                            ApplicationEventPublisher publisher, RpsimProperties props) {
        this.savegames = savegames;
        this.employees = employees;
        this.satisfaction = satisfaction;
        this.burden = burden;
        this.roles = roles;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.random = random;
        this.publisher = publisher;
        this.props = props;
    }

    private RpsimProperties.SickLeave cfg() {
        return props.getFormulas().getSickLeave();
    }

    /** True while a sickness or accident keeps the employee away. */
    public static boolean absent(Employee e, long now) {
        return e.getAbsenceKind() != null && e.getAbsenceUntilGameTime() != null && e.getAbsenceUntilGameTime() > now;
    }

    /** Accident risk factor from the needs workload and working conditions (0..100). */
    public static double riskFactor(double workload, double workingConditions, RpsimProperties.SickLeave cfg) {
        double f = 1;
        if (workload < cfg.getRiskBadThreshold()) {
            f *= cfg.getRiskBadFactor();
        }
        if (workingConditions < cfg.getRiskBadThreshold()) {
            f *= cfg.getRiskBadFactor();
        }
        if (workload >= cfg.getRiskGoodThreshold() && workingConditions >= cfg.getRiskGoodThreshold()) {
            f *= cfg.getRiskGoodFactor();
        }
        return f;
    }

    /** Daily, before the workforce sync: returns, then new absences. */
    @EventListener
    @Order(60)
    @Transactional
    public void onDay(GameDayPassedEvent ev) {
        Savegame sg = savegames.findById(ev.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        boolean changed = false;
        for (Employee e : employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE)) {
            if (e.getAbsenceKind() != null && !absent(e, now)) {
                back(sg, e);
                changed = true;
            } else if (e.getAbsenceKind() == null && roll(sg, e, now)) {
                changed = true;
            }
        }
        if (changed) {
            publisher.publishEvent(new RosterChangedEvent(sg.getId()));
        }
    }

    boolean roll(Savegame sg, Employee e, long now) {
        // owner decision 2026-10-06: a booked training (also the one of tomorrow) has precedence over sickness
        if (!cfg().isEnabled() || !burden.on(sg, Burden.SICK_LEAVE) || TrainingService.trainingBooked(e, now)
                || (e.getTimeOffUntilGameTime() != null && e.getTimeOffUntilGameTime() > now)) {
            return false;
        }
        double factor = burden.factor(sg);
        if (random.chance(cfg().getSicknessProbabilityPerDay() * factor)) {
            start(sg, e, SICKNESS, random.intBetween(cfg().getSicknessDaysMin(), cfg().getSicknessDaysMax()));
            return true;
        }
        if (cfg().getAccidentRoles().contains(e.getJobRole().name())) {
            SatisfactionService.Needs n = satisfaction.needs(e);
            double risk = riskFactor(n.workload(), n.workingConditions(), cfg());
            if (random.chance(cfg().getAccidentProbabilityPerDay() * risk * factor)) {
                start(sg, e, ACCIDENT, random.intBetween(cfg().getAccidentDaysMin(), cfg().getAccidentDaysMax()));
                return true;
            }
        }
        return false;
    }

    /** The absence starts: ON_LEAVE for the mod, reported by the office clerk (or the employee), diary. */
    @Transactional
    public void start(Savegame sg, Employee e, String kind, int days) {
        long now = sg.getCurrentGameTime();
        e.setAbsenceKind(kind);
        e.setAbsenceUntilGameTime(now + GameTime.days(days));
        e.setGetWellSent(false);
        String name = e.getCharacter().getName();
        Character reporter = employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.OFFICE_CLERK)
                .stream().filter(c -> !c.getId().equals(e.getId())).findFirst().map(Employee::getCharacter)
                .orElse(e.getCharacter());
        narration.request(sg, NarrationEventType.EMPLOYEE_SICK).from(reporter)
                .facts(NarrationFacts.builder().put("kind", kind).put("employeeName", name).put("days", days)
                        .put("ownReport", reporter == e.getCharacter()).build())
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, e.getId()).formLink("/employees").submit();
        if (ACCIDENT.equals(kind)) {
            narration.request(sg, NarrationEventType.WORK_ACCIDENT_REPORT).from(roles.ensure(sg, CharacterRole.SOCIAL_INSURANCE))
                    .facts(NarrationFacts.builder().put("employeeName", name).put("days", days).build())
                    .category(CommunicationCategory.CONTRACT).related(RELATED, e.getId()).submit();
        }
        diary.addAuto(sg, "EMPLOYEE", name + (ACCIDENT.equals(kind) ? " hatte einen Arbeitsunfall" : " ist krank"),
                "Fällt " + days + (days == 1 ? " Tag" : " Tage") + " aus, das Gehalt läuft weiter.", RELATED, e.getId());
    }

    void back(Savegame sg, Employee e) {
        String kind = e.getAbsenceKind();
        e.setAbsenceKind(null);
        e.setAbsenceUntilGameTime(null);
        e.setGetWellSent(false);
        diary.addAuto(sg, "EMPLOYEE", e.getCharacter().getName() + " ist wieder da", ACCIDENT.equals(kind)
                ? "Nach dem Arbeitsunfall wieder einsatzbereit." : "Wieder gesund und an der Arbeit.", RELATED, e.getId());
    }

    /** "Genesungswünsche": once per absence - appreciation, trust, a thank-you mail. */
    @Transactional
    public Employee getWell(Savegame sg, Employee e) {
        if (!absent(e, sg.getCurrentGameTime())) {
            throw new BusinessRuleException("NOT_ABSENT", e.getCharacter().getName() + " ist nicht krank.");
        }
        if (e.isGetWellSent()) {
            throw new BusinessRuleException("GET_WELL_SENT", "Die Genesungswünsche sind schon unterwegs.");
        }
        e.setGetWellSent(true);
        satisfaction.record(e, SatisfactionCategory.APPRECIATION, cfg().getGetWellAppreciation(), "Genesungswünsche");
        trust.recordEvent(e.getCharacter(), cfg().getGetWellTrustDelta(), TrustReason.GET_WELL_WISHES, "Genesungswünsche");
        narration.request(sg, NarrationEventType.EMPLOYEE_GET_WELL_THANKS).from(e.getCharacter())
                .facts(NarrationFacts.builder().put("kind", e.getAbsenceKind()).build())
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, e.getId()).submit();
        diary.addAuto(sg, "EMPLOYEE", "Genesungswünsche an " + e.getCharacter().getName(), "Gute Besserung gewünscht.",
                RELATED, e.getId());
        return e;
    }

    public Optional<Employee> find(Savegame sg, Long id) {
        return employees.findById(id).filter(e -> e.getSavegame().getId().equals(sg.getId()));
    }
}
