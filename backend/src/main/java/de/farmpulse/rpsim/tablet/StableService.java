package de.farmpulse.rpsim.tablet;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.api.Views.BarnView;
import de.farmpulse.rpsim.api.Views.ConditionView;
import de.farmpulse.rpsim.api.Views.StablesView;
import de.farmpulse.rpsim.api.Views.VetDueView;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.LivestockService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hof-Tablet app "Stall": the stables as the mod reports them (animals per husbandry from assets.animals, health,
 * productivity, food and conditions from farm_facts.husbandries), announced animal welfare inspections, the next
 * routine visit of the vet and the load of the animal keepers. Read only - the reactions stay in LivestockService,
 * AuthorityService and WorkforceService.
 */
@Service
public class StableService {

    private final FactsService facts;
    private final LivestockService livestock;
    private final ServiceCaseRepository cases;
    private final EmployeeRepository employees;
    private final GameTime gameTime;
    private final RpsimProperties props;

    public StableService(FactsService facts, LivestockService livestock, ServiceCaseRepository cases,
                         EmployeeRepository employees, GameTime gameTime, RpsimProperties props) {
        this.facts = facts;
        this.livestock = livestock;
        this.cases = cases;
        this.employees = employees;
        this.gameTime = gameTime;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public StablesView stables(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        var cfg = props.getFormulas().getLivestock();
        Map<String, Long> inspections = new HashMap<>();
        cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.AUTHORITY_INSPECTION)).stream()
                .filter(c -> c.getStatus() == CaseStatus.IN_PROGRESS && "ANIMAL_WELFARE".equals(c.getTitle())
                        && c.getReference() != null && c.getDeadlineGameTime() != null)
                .forEach(c -> inspections.put(c.getReference(), c.getDeadlineGameTime()));

        Map<String, BridgeDtos.Husbandry> byId = new HashMap<>();
        if (f != null && f.husbandries() != null) {
            f.husbandries().stream().filter(h -> h != null && h.husbandryUniqueId() != null)
                    .forEach(h -> byId.put(h.husbandryUniqueId(), h));
        }
        // one barn per husbandry of assets.animals (several animal entries of a husbandry are summed up)
        Map<String, BarnView> barns = new LinkedHashMap<>();
        List<BridgeDtos.Animal> animals = f == null || f.assets() == null || f.assets().animals() == null ? List.of()
                : f.assets().animals();
        for (BridgeDtos.Animal a : animals) {
            if (a == null || a.count() == null || a.count() <= 0) {
                continue;
            }
            String id = a.husbandryUniqueId() == null ? "?" + a.type() : a.husbandryUniqueId();
            BarnView prev = barns.get(id);
            int count = (prev == null ? 0 : prev.count()) + a.count();
            long value = (prev == null ? 0 : prev.value()) + Math.round(a.estimatedValue() == null ? 0 : a.estimatedValue());
            BridgeDtos.Husbandry h = byId.get(a.husbandryUniqueId());
            barns.put(id, new BarnView(id, a.type() == null ? "UNKNOWN" : a.type(), count, value,
                    h == null ? null : h.health(), h == null ? null : h.productivity(), h == null ? null : h.food(),
                    h == null ? null : livestock.water(h), h == null ? List.of() : conditions(h),
                    inspections.get(a.husbandryUniqueId())));
        }
        int total = barns.values().stream().mapToInt(BarnView::count).sum();
        int keepers = (int) employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.ANIMAL_KEEPER)
                .stream().filter(k -> k.getStrikeSinceGameTime() == null).count();
        return new StablesView(f != null && f.husbandries() != null, total, keepers,
                props.getFormulas().getSatisfaction().getWorkload().getAnimalsPerKeeper(),
                cfg.getVetEmergencyHealthThreshold(), cfg.getKeeperFoodWarningRatio(), cfg.getKeeperWaterWarningRatio(),
                new ArrayList<>(barns.values()), vetDue(sg, LivestockService.herds(f).keySet()));
    }

    private static List<ConditionView> conditions(BridgeDtos.Husbandry h) {
        return h.conditions() == null ? List.of() : h.conditions().stream()
                .filter(c -> c != null && c.title() != null && c.ratio() != null)
                .map(c -> new ConditionView(c.title(), c.ratio())).toList();
    }

    /**
     * Routine visits come at a month start once the last routine visit of the type is vet-visit-every-months old
     * (LivestockService.onMonth); without a visit so far at the next month start. Emergencies do not count.
     */
    List<VetDueView> vetDue(Savegame sg, Iterable<String> types) {
        long now = sg.getCurrentGameTime();
        int every = props.getFormulas().getLivestock().getVetVisitEveryMonths();
        long nextMonth = gameTime.monthIndex(sg, now) + 1;
        List<ServiceCase> visits = cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.VET_VISIT));
        List<VetDueView> out = new ArrayList<>();
        for (String type : types) {
            long due = visits.stream().filter(c -> type.equals(c.getReference()) && !"EMERGENCY".equals(c.getResolution()))
                    .findFirst().map(c -> Math.max(nextMonth, gameTime.monthIndex(sg, c.getGameTime()) + every))
                    .orElse(nextMonth);
            out.add(new VetDueView(type, gameTime.monthStart(sg, due)));
        }
        return out;
    }
}
