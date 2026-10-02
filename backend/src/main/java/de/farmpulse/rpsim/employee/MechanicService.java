package de.farmpulse.rpsim.employee;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.SatisfactionCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-A6: an employed mechanic repairs part of the machines at the start of every game month. After the
 * maintenance contract (MaintenanceService, @Order 75) each active mechanic who is not on strike spends a repair
 * capacity of {@code repair-points-per-month x skill / 100 x effectMultiplier} condition points on the most worn own
 * vehicles below {@code repair-below-condition}. A vehicle is never repaired twice in a month; the repair goes to the mod
 * as REPAIR_VEHICLE with {@code targetDamage}. Machines left broken cost the mechanic workload.
 */
@Service
public class MechanicService {

    public static final String RESOLUTION = "MECHANIC";
    /** Resolution of the repairs of the maintenance contract (MaintenanceService). */
    static final String CONTRACT_RESOLUTION = "INCLUDED";

    private final SavegameRepository savegames;
    private final EmployeeRepository employees;
    private final ServiceCaseRepository cases;
    private final FactsService facts;
    private final OutboxService outbox;
    private final SatisfactionService satisfaction;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RpsimProperties props;
    private final GameTime gameTime;

    /** Roadmap V3.1 R31-A2: the mechanic does not repair borrowed and demo machines (owner decision). */
    private final de.farmpulse.rpsim.farmwork.LoanedVehicles loaned;

    public MechanicService(SavegameRepository savegames, EmployeeRepository employees, ServiceCaseRepository cases,
                           FactsService facts, OutboxService outbox, SatisfactionService satisfaction,
                           NarrationRequestService narration, DiaryService diary, RpsimProperties props, GameTime gameTime,
                           de.farmpulse.rpsim.farmwork.LoanedVehicles loaned) {
        this.loaned = loaned;
        this.savegames = savegames;
        this.employees = employees;
        this.cases = cases;
        this.facts = facts;
        this.outbox = outbox;
        this.satisfaction = satisfaction;
        this.narration = narration;
        this.diary = diary;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Mechanic cfg() {
        return props.getFormulas().getMechanic();
    }

    /** One planned repair: vehicle, condition before and after (0..100). */
    public record Repair(String vehicleId, double conditionBefore, double conditionAfter) {
        public double targetDamage() {
            return 1 - conditionAfter / 100.0;
        }
    }

    /**
     * Spends the capacity on the most worn vehicles below the threshold (each repaired as far as the capacity lasts).
     * Pure calculation.
     */
    public static List<Repair> plan(List<BridgeDtos.Vehicle> vehicles, Set<String> excluded, double capacity,
                                    double belowCondition) {
        List<Repair> repairs = new ArrayList<>();
        double left = capacity;
        for (BridgeDtos.Vehicle v : vehicles.stream()
                .filter(v -> v.condition() < belowCondition && !excluded.contains(v.uniqueId()))
                .sorted(Comparator.comparingDouble(BridgeDtos.Vehicle::condition)).toList()) {
            if (left <= 0) {
                break;
            }
            double points = Math.min(100 - v.condition(), left);
            left -= points;
            repairs.add(new Repair(v.uniqueId(), v.condition(), v.condition() + points));
        }
        return repairs;
    }

    @EventListener
    @Order(76)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        List<Employee> mechanics = employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.MECHANIC)
                .stream().filter(m -> m.getStrikeSinceGameTime() == null).toList();
        if (mechanics.isEmpty()) {
            return;
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.assets() == null || f.assets().vehicles() == null) {
            return;
        }
        List<BridgeDtos.Vehicle> vehicles = loaned.own(sg, f.assets().vehicles()).stream()
                .filter(v -> v != null && v.uniqueId() != null && v.condition() != null).toList();
        Set<String> done = repairedThisMonth(sg);
        for (Employee m : mechanics) {
            work(sg, m, vehicles, done);
        }
    }

    /** Vehicles repaired this game month already (maintenance contract or another mechanic). */
    Set<String> repairedThisMonth(Savegame sg) {
        long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        Set<String> ids = new HashSet<>();
        cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.REPAIR)).stream()
                .filter(c -> CONTRACT_RESOLUTION.equals(c.getResolution()) || RESOLUTION.equals(c.getResolution()))
                .filter(c -> gameTime.monthIndex(sg, c.getGameTime()) == month)
                .forEach(c -> ids.add(c.getReference()));
        return ids;
    }

    @Transactional
    public List<Repair> work(Savegame sg, Employee m, List<BridgeDtos.Vehicle> vehicles, Set<String> done) {
        double capacity = cfg().getRepairPointsPerMonth() * m.getSkill() / 100.0 * satisfaction.needs(m).effectMultiplier();
        List<Repair> repairs = plan(vehicles, done, capacity, cfg().getRepairBelowCondition());
        for (Repair r : repairs) {
            ServiceCase sc = new ServiceCase();
            sc.setSavegame(sg);
            sc.setKind(CaseKind.REPAIR);
            sc.setCharacter(m.getCharacter());
            sc.setReference(r.vehicleId());
            sc.setQuantity((int) Math.round(r.conditionBefore()));
            sc.setStatus(CaseStatus.SETTLED);
            sc.setResolution(RESOLUTION);
            sc.setGameTime(sg.getCurrentGameTime());
            sc.setClosedAtGameTime(sg.getCurrentGameTime());
            sc.setCreatedAt(Instant.now());
            cases.save(sc);
            outbox.repairVehicle(sg, r.vehicleId(), r.targetDamage(), new Related(SatisfactionService.RELATED, m.getId()));
            done.add(r.vehicleId());
        }
        long pending = vehicles.stream()
                .filter(v -> v.condition() < cfg().getRepairBelowCondition() && !done.contains(v.uniqueId())).count();
        if (pending > 0) {
            satisfaction.record(m, SatisfactionCategory.WORKLOAD, -pending * cfg().getOverloadWorkloadPerVehicle(),
                    pending + " Maschinen warten auf Reparatur");
        }
        if (!repairs.isEmpty() || pending > 0) {
            long average = Math.round(repairs.stream().mapToDouble(Repair::conditionAfter).average().orElse(0));
            narration.request(sg, NarrationEventType.MECHANIC_REPORT).from(m.getCharacter())
                    .facts(NarrationFacts.builder().put("repairCount", repairs.size()).put("pendingCount", pending)
                            .put("averageConditionAfter", average).build())
                    .category(CommunicationCategory.EMPLOYEE).related(SatisfactionService.RELATED, m.getId()).submit();
        }
        if (!repairs.isEmpty()) {
            diary.addAuto(sg, "EMPLOYEE", m.getCharacter().getName() + " hat repariert", repairs.size()
                    + " Maschinen instand gesetzt" + (pending > 0 ? ", " + pending + " warten noch." : "."),
                    SatisfactionService.RELATED, m.getId());
        }
        return repairs;
    }
}
