package de.farmpulse.rpsim.employee;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-P2: apprentices (owner decisions in QUESTIONS.md). Skill +skill-per-month at every month start up to
 * skill-cap. takeover-notice-months before the end of the training the apprentice asks to be taken over as machine
 * operator for the salary of the operator formula at his skill (case APPRENTICE_TAKEOVER, deadline = end of the
 * training): accept, one counter offer (accepted from counter-accept-share of the demand, otherwise he leaves) or
 * decline. Without an agreement he leaves at the end of the training. Diary entry in every case.
 */
@Service
public class ApprenticeService {

    public static final String RELATED = "SERVICE_CASE";

    private final EmployeeRepository employees;
    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final ApplicationEventPublisher publisher;
    private final GameTime gameTime;
    private final RpsimProperties props;

    public ApprenticeService(EmployeeRepository employees, ServiceCaseRepository cases, SavegameRepository savegames,
                             NarrationRequestService narration, DiaryService diary, ApplicationEventPublisher publisher,
                             GameTime gameTime, RpsimProperties props) {
        this.employees = employees;
        this.cases = cases;
        this.savegames = savegames;
        this.narration = narration;
        this.diary = diary;
        this.publisher = publisher;
        this.gameTime = gameTime;
        this.props = props;
    }

    private RpsimProperties.Apprentice cfg() {
        return props.getFormulas().getApprentice();
    }

    private List<Employee> apprentices(Savegame sg) {
        return employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.APPRENTICE);
    }

    /** Salary of a machine operator with this skill (hiring formula, rounded to 10 €). */
    public long operatorSalary(int skill) {
        RpsimProperties.Hiring h = props.getFormulas().getHiring();
        double base = h.getBaseSalary().getOrDefault(JobRole.MACHINE_OPERATOR.name(), 2400.0);
        return Math.round(base * (1 + h.getSalarySkillFactor() * (skill - 50) / 50.0) / 10.0) * 10;
    }

    /** Month start: the apprentices learn. */
    @EventListener
    @Order(60)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        for (Employee a : apprentices(sg)) {
            a.setSkill(Math.min(cfg().getSkillCap(), a.getSkill() + cfg().getSkillPerMonth()));
        }
    }

    /** Daily: the takeover request before the end, the end of the training without an agreement. */
    @EventListener
    @Order(61)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        // a request of an apprentice who left (dismissed) is closed
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.APPRENTICE_TAKEOVER))) {
            if (sc.getStatus() == CaseStatus.AWAITING_PLAYER && employees.findById(Long.parseLong(sc.getReference()))
                    .filter(x -> x.getStatus() == EmployeeStatus.ACTIVE && x.getJobRole() == JobRole.APPRENTICE).isEmpty()) {
                sc.setStatus(CaseStatus.EXPIRED);
                sc.setResolution("EMPLOYEE_GONE");
                sc.setClosedAtGameTime(now);
            }
        }
        for (Employee a : apprentices(sg)) {
            Long end = a.getApprenticeshipEndsAtGameTime();
            if (end == null) {
                continue;
            }
            Optional<ServiceCase> request = request(sg, a);
            if (now >= end) {
                request.filter(sc -> sc.getStatus() == CaseStatus.AWAITING_PLAYER).ifPresent(sc -> {
                    sc.setStatus(CaseStatus.EXPIRED);
                    sc.setResolution("NO_ANSWER");
                    sc.setClosedAtGameTime(now);
                });
                leave(sg, a, "Die Ausbildung ist zu Ende – ohne Übernahme verlässt " + a.getCharacter().getName()
                        + " den Hof.");
            } else if (request.isEmpty() && now >= gameTime.addMonths(sg, end, -cfg().getTakeoverNoticeMonths())) {
                askForTakeover(sg, a, end);
            }
        }
    }

    Optional<ServiceCase> request(Savegame sg, Employee a) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.APPRENTICE_TAKEOVER)).stream()
                .filter(sc -> String.valueOf(a.getId()).equals(sc.getReference())).findFirst();
    }

    @Transactional
    public ServiceCase askForTakeover(Savegame sg, Employee a, long end) {
        long demand = operatorSalary(a.getSkill());
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.APPRENTICE_TAKEOVER);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(a.getCharacter());
        sc.setReference(String.valueOf(a.getId()));
        sc.setOfferAmount(demand);
        sc.setQuantity(a.getSkill());
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(end);
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        narration.request(sg, NarrationEventType.APPRENTICE_TAKEOVER_REQUEST).from(a.getCharacter())
                .facts(NarrationFacts.builder().put("salary", demand).put("skill", a.getSkill())
                        .put("daysLeft", Math.max(0, Math.round(GameTime.toDays(end - sg.getCurrentGameTime())))).build())
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, sc.getId())
                .formLink("/employees?case=" + sc.getId()).submit();
        return sc;
    }

    // ------------------------------------------------------------------------------------------ player decisions

    private ServiceCase open(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (sc.getKind() != CaseKind.APPRENTICE_TAKEOVER || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Über die Übernahme ist bereits entschieden.");
        }
        return sc;
    }

    private Employee apprentice(ServiceCase sc) {
        Employee a = employees.findById(Long.parseLong(sc.getReference())).orElseThrow();
        if (a.getStatus() != EmployeeStatus.ACTIVE || a.getJobRole() != JobRole.APPRENTICE) {
            throw new BusinessRuleException("EMPLOYEE_INACTIVE", "Der Azubi ist nicht mehr auf dem Hof.");
        }
        return a;
    }

    /** "Übernehmen" at the demanded salary. */
    @Transactional
    public ServiceCase accept(Savegame sg, Long caseId) {
        ServiceCase sc = open(sg, caseId);
        takeOver(sg, sc, apprentice(sc), sc.getOfferAmount());
        return sc;
    }

    /** One counter offer: accepted from counter-accept-share of the demand, otherwise he leaves at the end. */
    @Transactional
    public ServiceCase counter(Savegame sg, Long caseId, long amount) {
        ServiceCase sc = open(sg, caseId);
        Employee a = apprentice(sc);
        sc.setCostAmount(amount);
        if (amount >= Math.round(sc.getOfferAmount() * cfg().getCounterAcceptShare())) {
            takeOver(sg, sc, a, amount);
            return sc;
        }
        refused(sg, sc, a, "COUNTER_REJECTED");
        return sc;
    }

    /** "Nicht übernehmen": he finishes the training and leaves at its end. */
    @Transactional
    public ServiceCase decline(Savegame sg, Long caseId) {
        ServiceCase sc = open(sg, caseId);
        refused(sg, sc, apprentice(sc), "DECLINED");
        return sc;
    }

    private void takeOver(Savegame sg, ServiceCase sc, Employee a, long salary) {
        long now = sg.getCurrentGameTime();
        a.setJobRole(JobRole.MACHINE_OPERATOR);
        a.setMonthlySalary(salary);
        a.setApprenticeshipEndsAtGameTime(null);
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("ACCEPTED");
        sc.setPayoutAmount(salary);
        sc.setClosedAtGameTime(now);
        publisher.publishEvent(new RosterChangedEvent(sg.getId()));
        narration.request(sg, NarrationEventType.APPRENTICE_TAKEN_OVER).from(a.getCharacter())
                .facts(NarrationFacts.builder().put("salary", salary).build())
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "EMPLOYEE", a.getCharacter().getName() + " übernommen", "Nach der Ausbildung jetzt "
                + "Maschinenführer/in für " + salary + " €/Monat.", SatisfactionService.RELATED, a.getId());
    }

    /** The apprentice did not get what he asked: he finishes the training and leaves at its end (daily check). */
    private void refused(Savegame sg, ServiceCase sc, Employee a, String resolution) {
        sc.setStatus(CaseStatus.DECLINED);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        narration.request(sg, NarrationEventType.APPRENTICE_LEAVES).from(a.getCharacter())
                .facts(NarrationFacts.builder().put("reason", resolution)
                        .put("daysLeft", Math.max(0, Math.round(GameTime.toDays(
                                a.getApprenticeshipEndsAtGameTime() - sg.getCurrentGameTime())))).build())
                .category(CommunicationCategory.EMPLOYEE).related(RELATED, sc.getId()).submit();
    }

    private void leave(Savegame sg, Employee a, String text) {
        long now = sg.getCurrentGameTime();
        a.setStatus(EmployeeStatus.TERMINATED);
        a.setTerminatedAtGameTime(now);
        a.getCharacter().setStatus(CharacterStatus.TERMINATED);
        a.getCharacter().setTerminationReason(TerminationReason.RESIGNED);
        a.getCharacter().setLeftAtGameTime(now);
        diary.addAuto(sg, "EMPLOYEE", a.getCharacter().getName() + " geht nach der Ausbildung", text,
                SatisfactionService.RELATED, a.getId());
        publisher.publishEvent(new RosterChangedEvent(sg.getId()));
    }
}
