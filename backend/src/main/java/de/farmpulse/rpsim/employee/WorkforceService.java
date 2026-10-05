package de.farmpulse.rpsim.employee;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.HelperWageMode;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.SatisfactionCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap V2 R2-A: employees as FS25 helpers.
 * <ul>
 *   <li>A0: the complete employee list goes to the mod (EMPLOYEE_ROSTER) whenever it changes and again after a rewind
 *   (reload without saving). List order = skill, highest first - the mod assigns the first free active machine
 *   operator.</li>
 *   <li>A4: the worked game time from {@code farm_facts.workforce} is counted per employee (today / this month); every
 *   game day the workload of machine operators follows the real hours instead of the simulated decay.</li>
 *   <li>A7: every game day the workload of animal keepers follows the animals per keeper.</li>
 *   <li>"Schulungen": every employee carries his finished trainings, the list the FS25 shop categories per training;
 *   an employee at a training is ON_LEAVE for the mod.</li>
 * </ul>
 */
@Service
public class WorkforceService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ON_LEAVE = "ON_LEAVE";
    public static final String STATUS_STRIKE = "STRIKE";

    private final SavegameRepository savegames;
    private final EmployeeRepository employees;
    private final FactsService facts;
    private final OutboxService outbox;
    private final SatisfactionService satisfaction;
    private final TrainingService training;
    private final JsonMapper json;
    private final RpsimProperties props;

    public WorkforceService(SavegameRepository savegames, EmployeeRepository employees, FactsService facts,
                            OutboxService outbox, SatisfactionService satisfaction, TrainingService training,
                            JsonMapper json, RpsimProperties props) {
        this.training = training;
        this.savegames = savegames;
        this.employees = employees;
        this.facts = facts;
        this.outbox = outbox;
        this.satisfaction = satisfaction;
        this.json = json;
        this.props = props;
    }

    private RpsimProperties.Workload cfg() {
        return props.getFormulas().getSatisfaction().getWorkload();
    }

    /** Status of an employee in the list for the mod. */
    public static String rosterStatus(Employee e, long now) {
        if (e.getStrikeSinceGameTime() != null) {
            return STATUS_STRIKE;
        }
        if (e.getTimeOffUntilGameTime() != null && e.getTimeOffUntilGameTime() > now) {
            return STATUS_ON_LEAVE;
        }
        if (TrainingService.inTraining(e, now)) {
            return STATUS_ON_LEAVE; // at a training: no helper
        }
        if (SickLeaveService.absent(e, now)) {
            return STATUS_ON_LEAVE; // Roadmap V3.1 R31-B5: sick or after a work accident
        }
        return STATUS_ACTIVE;
    }

    /** The list as sent to the mod: all employees, highest skill first (ties by id). */
    public List<Map<String, Object>> roster(Savegame sg) {
        long now = sg.getCurrentGameTime();
        List<Map<String, Object>> list = new ArrayList<>();
        employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE).stream()
                .sorted(Comparator.comparingInt(Employee::getSkill).reversed().thenComparing(Employee::getId))
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("employeeId", e.getId());
                    m.put("name", e.getCharacter().getName());
                    m.put("role", e.getJobRole().name());
                    m.put("status", rosterStatus(e, now));
                    m.put("trainings", e.trainingSet().stream().map(Enum::name).toList());
                    list.add(m);
                });
        return list;
    }

    /** A0: sends the list when its content changed since the last one sent. Returns true when sent. */
    @Transactional
    public boolean sync(Savegame sg) {
        training.completeDue(sg); // a finished training changes the list
        List<Map<String, Object>> list = roster(sg);
        String mode = (sg.getHelperWageMode() == null ? HelperWageMode.EMPLOYEES : sg.getHelperWageMode()).name();
        if (sg.getRosterJson() == null && list.isEmpty() && HelperWageMode.EMPLOYEES.name().equals(mode)
                && !sg.isStrictHelperLimit()) {
            return false; // nothing to tell the mod yet (no employees, default switches)
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("employees", list);
        content.put("helperWageMode", mode);
        content.put("strictHelperLimit", sg.isStrictHelperLimit());
        Map<String, List<String>> categories = training.vehicleCategories();
        content.put("trainingCategories", categories);
        String text = json.writeValueAsString(content);
        if (Objects.equals(text, sg.getRosterJson())) {
            return false;
        }
        outbox.employeeRoster(sg, list, mode, sg.isStrictHelperLimit(), categories);
        sg.setRosterJson(text);
        sg.setRosterSentGameTime(sg.getCurrentGameTime());
        return true;
    }

    @EventListener
    @Transactional
    public void onRosterChanged(RosterChangedEvent e) {
        savegames.findById(e.savegameId()).ifPresent(this::sync);
    }

    /** A0 / A4 / A7: every farm_facts.json - worked time, tracking flags, list after a rewind or a change. */
    @EventListener
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null) {
            return;
        }
        if (sg.getRosterSentGameTime() != null && e.gameTime() < sg.getRosterSentGameTime()) {
            sg.setRosterJson(sg.getRosterJson() == null ? null : ""); // reload without saving: send the list again
        }
        sg.setHusbandriesTracked(f.husbandries() != null);
        if (f.workforce() != null && f.workforce().workedGameMs() != null) {
            sg.setWorkforceTracked(true);
            creditWorkedTime(sg, f.workforce().workedGameMs());
        } else {
            sg.setWorkforceTracked(false);
        }
        sync(sg);
    }

    /** Takes the new cumulative values of the mod; a value that went back (reload) only moves the reference point. */
    void creditWorkedTime(Savegame sg, Map<String, Long> worked) {
        for (Employee emp : employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE)) {
            Long cumulative = worked.get(String.valueOf(emp.getId()));
            if (cumulative == null) {
                continue;
            }
            long seen = emp.getWorkedMsSeen() == null ? 0 : emp.getWorkedMsSeen();
            long delta = cumulative - seen;
            if (delta > 0) {
                emp.setWorkedMsToday(emp.getWorkedMsToday() + delta);
                emp.setWorkedMsMonth(emp.getWorkedMsMonth() + delta);
            }
            emp.setWorkedMsSeen(cumulative);
        }
    }

    /** Workload change of a machine operator for one game day with the given worked hours. */
    public double operatorWorkloadDelta(double hours) {
        double target = cfg().getTargetHoursPerDay();
        return hours > target ? -(hours - target) * cfg().getOvertimePenaltyPerHour()
                : (target - hours) * cfg().getRecoveryPerHour();
    }

    /** Workload change of an animal keeper for one game day with the given animals per keeper. */
    public double keeperWorkloadDelta(double animalsPerKeeper) {
        double load = animalsPerKeeper / cfg().getAnimalsPerKeeper();
        return load > 1 ? -(load - 1) * cfg().getKeeperOverloadPenaltyPerDay() : cfg().getKeeperRecoveryPerDay();
    }

    /** A4 / A7: daily workload from the real game (before the escalation check of the same day). */
    @EventListener
    @Order(10)
    @Transactional
    public void onDay(GameDayPassedEvent ev) {
        Savegame sg = savegames.findById(ev.savegameId()).orElseThrow();
        List<Employee> active = employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE);
        if (sg.isWorkforceTracked()) {
            for (Employee e : active) {
                if (!e.getJobRole().drives()) { // R3-P2: apprentices drive too
                    continue;
                }
                double hours = e.getWorkedMsToday() / (double) GameTime.hours(1);
                e.setWorkedMsToday(0);
                double delta = operatorWorkloadDelta(hours);
                if (Math.abs(delta) >= 0.01) {
                    satisfaction.record(e, SatisfactionCategory.WORKLOAD, delta,
                            String.format(java.util.Locale.GERMANY, "Arbeitszeit %.1f h", hours));
                }
            }
        }
        if (sg.isHusbandriesTracked()) {
            List<Employee> keepers = active.stream().filter(e -> e.getJobRole() == JobRole.ANIMAL_KEEPER).toList();
            if (!keepers.isEmpty()) {
                double animals = facts.latest(sg).map(FactsService::animalCount).orElse(0);
                double delta = keeperWorkloadDelta(animals / keepers.size());
                for (Employee k : keepers) {
                    satisfaction.record(k, SatisfactionCategory.WORKLOAD, delta,
                            Math.round(animals / keepers.size()) + " Tiere je Pfleger");
                }
            }
        }
        sync(sg); // a day off may have ended
    }

    /** Hours of the current game month, null without worked time from the mod. */
    public static Double hoursThisMonth(Employee e) {
        return e.getSavegame().isWorkforceTracked() && e.getJobRole().drives()
                ? e.getWorkedMsMonth() / (double) GameTime.hours(1) : null;
    }

    public static Double hoursLastMonth(Employee e) {
        return e.getSavegame().isWorkforceTracked() && e.getJobRole().drives()
                ? e.getWorkedMsLastMonth() / (double) GameTime.hours(1) : null;
    }
}
