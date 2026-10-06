package de.farmpulse.rpsim.employee;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.SatisfactionCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.Training;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner decision "Schulungen": a machine operator drives small and medium tractors (and every vehicle no training is
 * mapped to) without a training; large tractors, combines, forage harvesters, special harvesters, trucks and
 * self-propelled machines / loaders need the matching training. A training costs money (MONEY_TRANSACTION TRAINING),
 * the employee is away for rpsim.formulas.training.duration-days (ON_LEAVE for the mod, no helper) and gains
 * appreciation. The qualification counts once the training is over.
 * <p>
 * Owner decision 2026-10-06: the employee works normally on the booking day and is away for whole game days from the
 * start of the next game day (0:00) on. The booked training has precedence: no days off overlapping it and no sickness
 * while it is booked.
 */
@Service
public class TrainingService {

    private final EmployeeRepository employees;
    private final OutboxService outbox;
    private final SatisfactionService satisfaction;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RpsimProperties props;
    private final ApplicationEventPublisher publisher;

    public TrainingService(EmployeeRepository employees, OutboxService outbox, SatisfactionService satisfaction,
                           NarrationRequestService narration, DiaryService diary, RpsimProperties props,
                           ApplicationEventPublisher publisher) {
        this.employees = employees;
        this.outbox = outbox;
        this.satisfaction = satisfaction;
        this.narration = narration;
        this.diary = diary;
        this.props = props;
        this.publisher = publisher;
    }

    private RpsimProperties.Trainings cfg() {
        return props.getFormulas().getTraining();
    }

    public long cost(Training t) {
        return cfg().getCost().getOrDefault(t.name(), 0L);
    }

    /** One catalog entry: the training, its price and the FS25 shop categories it unlocks. */
    public record Offer(Training training, long cost, List<String> categories) {
    }

    public List<Offer> catalog() {
        List<Offer> list = new ArrayList<>();
        for (Training t : Training.values()) {
            list.add(new Offer(t, cost(t), cfg().getCategories().getOrDefault(t.name(), List.of())));
        }
        return list;
    }

    /** Training -> FS25 shop categories as sent to the mod with the employee list (EMPLOYEE_ROSTER). */
    public Map<String, List<String>> vehicleCategories() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (Training t : Training.values()) {
            List<String> cats = cfg().getCategories().getOrDefault(t.name(), List.of());
            if (!cats.isEmpty()) {
                m.put(t.name(), cats.stream().map(c -> c.toUpperCase(java.util.Locale.ROOT)).toList());
            }
        }
        return m;
    }

    /** The employee is at a training right now (away, no helper). */
    public static boolean inTraining(Employee e, long now) {
        return trainingBooked(e, now) && (e.getTrainingFromGameTime() == null || e.getTrainingFromGameTime() <= now);
    }

    /** A training is booked and not over yet - it may still lie ahead (the next game day). */
    public static boolean trainingBooked(Employee e, long now) {
        return e.getTrainingInProgress() != null && e.getTrainingUntilGameTime() != null
                && e.getTrainingUntilGameTime() > now;
    }

    /** The booked training overlaps the period [from, until). */
    public static boolean trainingOverlaps(Employee e, long from, long until) {
        if (!trainingBooked(e, from)) {
            return false;
        }
        long start = e.getTrainingFromGameTime() == null ? from : e.getTrainingFromGameTime();
        return start < until && e.getTrainingUntilGameTime() > from;
    }

    /** Owner decision 2026-10-06: the training starts with the next game day (0:00). */
    static long trainingStart(long now) {
        return (GameTime.dayIndex(now) + 1) * GameTime.MS_PER_DAY;
    }

    /** Books a training: money, absence of duration-days from the next game day on, appreciation and a thank-you mail. */
    @Transactional
    public Employee book(Employee e, Training t) {
        Savegame sg = e.getSavegame();
        long now = sg.getCurrentGameTime();
        completeDue(sg);
        if (e.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessRuleException("EMPLOYEE_INACTIVE", "Mitarbeiter ist nicht mehr angestellt.");
        }
        if (e.getJobRole() != JobRole.MACHINE_OPERATOR) {
            throw new BusinessRuleException("TRAINING_NOT_OPERATOR", "Schulungen gibt es nur für Maschinenführer.");
        }
        if (e.hasTraining(t)) {
            throw new BusinessRuleException("TRAINING_DONE", "Diese Schulung hat der Mitarbeiter bereits.");
        }
        if (e.getTrainingInProgress() != null) {
            throw new BusinessRuleException("TRAINING_RUNNING", "Der Mitarbeiter ist schon für eine Schulung angemeldet.");
        }
        if (e.getStrikeSinceGameTime() != null) {
            throw new BusinessRuleException("TRAINING_STRIKE", "Während eines Streiks geht niemand auf eine Schulung.");
        }
        if (e.getTimeOffUntilGameTime() != null && e.getTimeOffUntilGameTime() > now) {
            throw new BusinessRuleException("TRAINING_ON_LEAVE", "Der Mitarbeiter hat gerade frei.");
        }
        if (SickLeaveService.absent(e, now)) { // R31-B5
            throw new BusinessRuleException("TRAINING_SICK", "Der Mitarbeiter ist gerade krank.");
        }
        long cost = cost(t);
        if (cost > 0) {
            outbox.money(sg, -cost, MoneyReason.TRAINING, "Schulung " + t.title() + " – " + e.getCharacter().getName(),
                    new Related(SatisfactionService.RELATED, e.getId()));
        }
        long from = trainingStart(now);
        e.setTrainingInProgress(t);
        e.setTrainingFromGameTime(from);
        e.setTrainingUntilGameTime(from + GameTime.days(cfg().getDurationDays()));
        if (cfg().getAppreciationPoints() != 0) {
            satisfaction.record(e, SatisfactionCategory.APPRECIATION, cfg().getAppreciationPoints(), "Schulung " + t.title());
        }
        narration.request(sg, NarrationEventType.EMPLOYEE_THANKS).from(e.getCharacter())
                .facts(NarrationFacts.builder().put("reason", "TRAINING").put("training", t.title())
                        .put("salary", e.getMonthlySalary()).build())
                .category(CommunicationCategory.EMPLOYEE).related(SatisfactionService.RELATED, e.getId()).submit();
        diary.addAuto(sg, "EMPLOYEE", e.getCharacter().getName() + " zur Schulung angemeldet",
                "Schulung „" + t.title() + "“ gebucht (" + cost + " €), morgen den ganzen Tag.", SatisfactionService.RELATED,
                e.getId());
        publisher.publishEvent(new RosterChangedEvent(sg.getId())); // ON_LEAVE for the mod only from the next day on
        return e;
    }

    /** Finishes every training whose time is over; returns true when a qualification was added. */
    @Transactional
    public boolean completeDue(Savegame sg) {
        long now = sg.getCurrentGameTime();
        boolean changed = false;
        for (Employee e : employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE)) {
            if (e.getTrainingInProgress() != null && !trainingBooked(e, now)) {
                Training t = e.getTrainingInProgress();
                e.addTraining(t);
                e.setTrainingInProgress(null);
                e.setTrainingFromGameTime(null);
                e.setTrainingUntilGameTime(null);
                diary.addAuto(sg, "EMPLOYEE", e.getCharacter().getName() + " hat die Schulung abgeschlossen",
                        "Schulung „" + t.title() + "“ bestanden – ab jetzt als Helfer auf diesen Maschinen einsetzbar.",
                        SatisfactionService.RELATED, e.getId());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * T-03: the mod refused the booking (e.g. an older mod without the reason TRAINING) - the training is cancelled, or
     * taken back when it was already finished.
     */
    @Transactional
    public boolean onBookingFailed(Long employeeId, String note) {
        Employee e = employees.findById(employeeId).orElse(null);
        if (e == null) {
            return false;
        }
        Training t = e.getTrainingInProgress();
        if (t == null) {
            t = java.util.Arrays.stream(Training.values())
                    .filter(x -> note != null && note.startsWith("Schulung " + x.title() + " –") && e.hasTraining(x))
                    .findFirst().orElse(null);
            if (t == null) {
                return false;
            }
            e.removeTraining(t);
        }
        e.setTrainingInProgress(null);
        e.setTrainingFromGameTime(null);
        e.setTrainingUntilGameTime(null);
        diary.addAuto(e.getSavegame(), "EMPLOYEE", "Schulung von " + e.getCharacter().getName() + " storniert",
                "Die Buchung der Schulung „" + t.title() + "“ ist fehlgeschlagen.", SatisfactionService.RELATED, e.getId());
        publisher.publishEvent(new RosterChangedEvent(e.getSavegame().getId()));
        return true;
    }
}
