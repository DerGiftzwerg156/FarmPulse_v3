package de.farmpulse.rpsim.theft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.authority.BurdeningEvents;
import de.farmpulse.rpsim.authority.BurdeningEvents.Burden;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.InsuranceService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.DieselTheft;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TankLock;
import de.farmpulse.rpsim.farmwork.LoanedVehicles;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.newspaper.VillageNewsService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.DieselTheftRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.TankLockRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-D8 (owner decisions 2026-10-05): diesel theft. At a month start with {@code probability-per-month}
 * (switch per savegame, off in the world mode IDYLLIC) a target is picked among the own vehicles of the latest export
 * with at least {@code min-liters} diesel ({@code assets.vehicles[].fuel}), not being driven (not in
 * {@code vehiclePositions}) and no borrowed or demo machine; a tank lock multiplies the weight by
 * {@code tank-lock-factor}. {@code share-min}..{@code share-max} of the level, at most {@code max-liters}, are taken in
 * the next night: VEHICLE_FUEL with {@code gameTimeEarliest} = the next {@code night-work.night-start-hour}. A refusal of
 * the mod is tried again the next night, at most {@code max-attempts} times. Afterwards the police (role POLICE) sends
 * the report, the village gossips (newspaper / chat) and the module "Diebstahl" of an active storm / hail insurance pays
 * a damage (litres × {@code diesel-price-per-liter}) above {@code insurance-min-damage} in full.
 */
@Service
public class DieselTheftService {

    public static final String RELATED = "DIESEL_THEFT";
    public static final String TANK_LOCK_RELATED = "TANK_LOCK";

    private final DieselTheftRepository thefts;
    private final TankLockRepository locks;
    private final ContractRepository contracts;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final LoanedVehicles loaned;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final VillageNewsService news;
    private final DiaryService diary;
    private final BurdeningEvents burdens;
    private final RandomSource random;
    private final RpsimProperties props;

    public DieselTheftService(DieselTheftRepository thefts, TankLockRepository locks, ContractRepository contracts,
                              SavegameRepository savegames, FactsService facts, LoanedVehicles loaned,
                              LiquidityService liquidity, OutboxService outbox, ServiceRoleService roles,
                              NarrationRequestService narration, VillageNewsService news, DiaryService diary,
                              BurdeningEvents burdens, RandomSource random, RpsimProperties props) {
        this.thefts = thefts;
        this.locks = locks;
        this.contracts = contracts;
        this.savegames = savegames;
        this.facts = facts;
        this.loaned = loaned;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.roles = roles;
        this.narration = narration;
        this.news = news;
        this.diary = diary;
        this.burdens = burdens;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.DieselTheft cfg() {
        return props.getFormulas().getDieselTheft();
    }

    /** Start of the next night (night-work.night-start-hour) after {@code now}. */
    long nextNight(long now) {
        long start = GameTime.dayIndex(now) * GameTime.days(1) + GameTime.hours(props.getFormulas().getNightWork().getNightStartHour());
        return now < start ? start : start + GameTime.days(1);
    }

    @EventListener
    @Order(91)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled() || !burdens.on(sg, Burden.DIESEL_THEFT) || running(sg).isPresent()) {
            return;
        }
        if (random.chance(cfg().getProbabilityPerMonth())) {
            plan(sg);
        }
    }

    private Optional<DieselTheft> running(Savegame sg) {
        return thefts.findBySavegameOrderByIdDesc(sg).stream()
                .filter(t -> DieselTheft.PLANNED.equals(t.getStatus()) || DieselTheft.SENT.equals(t.getStatus())).findFirst();
    }

    /** Vehicles a thief would pick, with their weight (a tank lock lowers it). */
    Map<BridgeDtos.Vehicle, Double> targets(Savegame sg, FarmFacts f) {
        Map<BridgeDtos.Vehicle, Double> out = new LinkedHashMap<>();
        if (f == null || f.assets() == null || f.assets().vehicles() == null) {
            return out;
        }
        Set<String> driven = f.vehiclePositions() == null ? Set.of() : f.vehiclePositions().stream()
                .map(BridgeDtos.VehiclePosition::uniqueId).collect(Collectors.toSet());
        Set<String> locked = locks.findBySavegameOrderByIdAsc(sg).stream().map(TankLock::getVehicleId).collect(Collectors.toSet());
        for (BridgeDtos.Vehicle v : loaned.own(sg, f.assets().vehicles())) {
            if (v.uniqueId() == null || v.fuel() == null || v.fuel().liters() == null || v.fuel().liters() < cfg().getMinLiters()
                    || driven.contains(v.uniqueId())) {
                continue;
            }
            out.put(v, locked.contains(v.uniqueId()) ? cfg().getTankLockFactor() : 1.0);
        }
        return out;
    }

    /** Picks the target and queues the theft for the next night. */
    @Transactional
    public Optional<DieselTheft> plan(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Map<BridgeDtos.Vehicle, Double> targets = targets(sg, f);
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        BridgeDtos.Vehicle v = random.weighted(targets);
        double share = random.uniform(cfg().getShareMin(), cfg().getShareMax());
        long liters = Math.max(1, Math.round(Math.min(cfg().getMaxLiters(), v.fuel().liters() * share)));
        DieselTheft t = new DieselTheft();
        t.setSavegame(sg);
        t.setVehicleId(v.uniqueId());
        t.setVehicleName(v.name());
        t.setLiters(liters);
        t.setStatus(DieselTheft.PLANNED);
        t.setPlannedGameTime(nextNight(sg.getCurrentGameTime()));
        thefts.save(t);
        send(sg, t);
        return Optional.of(t);
    }

    private void send(Savegame sg, DieselTheft t) {
        t.setAttempts(t.getAttempts() + 1);
        OutboxInstruction ins = outbox.vehicleFuel(sg, t.getVehicleId(), -t.getLiters(), t.getPlannedGameTime(),
                new Related(RELATED, t.getId()));
        t.setInstructionId(ins.getInstructionId());
        t.setStatus(DieselTheft.SENT);
    }

    /** The mod took the diesel: police report, gossip and the insurance module. */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        DieselTheft t = thefts.findById(e.relatedId()).orElse(null);
        if (t == null || !DieselTheft.SENT.equals(t.getStatus()) || !e.instructionId().equals(t.getInstructionId())) {
            return;
        }
        Savegame sg = t.getSavegame();
        Object taken = e.result() == null ? null : e.result().get("liters");
        long liters = taken instanceof Number n ? Math.round(Math.abs(n.doubleValue())) : t.getLiters();
        done(sg, t, liters);
    }

    void done(Savegame sg, DieselTheft t, long liters) {
        t.setStatus(DieselTheft.DONE);
        t.setStolenLiters(liters);
        t.setClosedGameTime(sg.getCurrentGameTime());
        if (liters <= 0) {
            return;
        }
        long damage = Math.round(liters * cfg().getDieselPricePerLiter());
        t.setDamage(damage);
        String name = t.getVehicleName() == null || t.getVehicleName().isBlank() ? "einer Maschine" : t.getVehicleName();
        narration.request(sg, NarrationEventType.DIESEL_THEFT_REPORT).from(roles.ensure(sg, CharacterRole.POLICE))
                .facts(NarrationFacts.builder().put("vehicleName", name).put("liters", liters).build())
                .category(CommunicationCategory.GENERAL).related(RELATED, t.getId()).submit();
        news.add(sg, VillageNewsService.Section.VILLAGE, "DIESEL_THEFT",
                "In der Nacht wurde auf einem Hof Diesel abgezapft – die Polizei bittet um Hinweise.");
        diary.addAuto(sg, "OTHER", "Dieseldiebstahl", liters + " l Diesel aus " + name + " gestohlen.", RELATED, t.getId());
        Optional<Contract> cover = insurance(sg).filter(Contract::isTheftCover);
        if (cover.isPresent() && damage > cfg().getInsuranceMinDamage()) {
            t.setInsurancePayout(damage);
            outbox.money(sg, damage, MoneyReason.INSURANCE_PAYOUT, "Versicherung: Dieseldiebstahl", new Related(RELATED, t.getId()));
            narration.request(sg, NarrationEventType.DIESEL_THEFT_INSURANCE).from(cover.get().getCharacter())
                    .facts(NarrationFacts.builder().put("payout", damage).put("liters", liters).build())
                    .category(CommunicationCategory.INSURANCE).related(RELATED, t.getId()).submit();
        }
    }

    /**
     * FailedInstructionService: the mod refused (vehicle in use, no diesel tank ...) - the next night again, at most
     * {@code max-attempts} times; an older mod (NOT_SUPPORTED) ends the theft. Returns true when the theft was running.
     */
    @Transactional
    public boolean onInstructionFailed(Long theftId, String message) {
        DieselTheft t = thefts.findById(theftId).orElse(null);
        if (t == null || !DieselTheft.SENT.equals(t.getStatus())) {
            return false;
        }
        Savegame sg = t.getSavegame();
        boolean outdated = message != null && (message.contains("NOT_SUPPORTED") || message.contains("unknown type"));
        boolean permanent = message != null && (message.contains("VEHICLE_NOT_FOUND") || message.contains("NOT_OWN_VEHICLE")
                || message.contains("NO_DIESEL_TANK"));
        if (outdated || permanent || t.getAttempts() >= cfg().getMaxAttempts()) {
            t.setStatus(DieselTheft.FAILED);
            t.setClosedGameTime(sg.getCurrentGameTime());
            return true;
        }
        t.setPlannedGameTime(nextNight(sg.getCurrentGameTime()));
        send(sg, t);
        return true;
    }

    // ------------------------------------------------------------------------------------------ insurance, tank lock

    /** The active storm / hail insurance (not the drought index insurance of R3-W1). */
    private Optional<Contract> insurance(Savegame sg) {
        return contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.INSURANCE, List.of(ContractStatus.ACTIVE))
                .stream().filter(c -> !InsuranceService.DROUGHT.equals(c.getLevel())).findFirst();
    }

    /** Module "Diebstahl" of the active storm / hail insurance on or off (+ / − the monthly premium). */
    @Transactional
    public Contract theftCover(Savegame sg, Long contractId, boolean on) {
        Contract c = contracts.findById(contractId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("contract " + contractId));
        if (c.getKind() != ContractKind.INSURANCE || c.getStatus() != ContractStatus.ACTIVE
                || InsuranceService.DROUGHT.equals(c.getLevel())) {
            throw new BusinessRuleException("NOT_ACTIVE_INSURANCE", "Der Baustein gehört zu einer laufenden Sturm- und Hagelversicherung.");
        }
        if (c.isTheftCover() == on) {
            return c;
        }
        c.setTheftCover(on);
        c.setMonthlyAmount(c.getMonthlyAmount() + (on ? 1 : -1) * cfg().getInsurancePremiumPerMonth());
        diary.addAuto(sg, "INSURANCE", on ? "Baustein Diebstahl abgeschlossen" : "Baustein Diebstahl gekündigt",
                (on ? "+" : "−") + cfg().getInsurancePremiumPerMonth() + " € Prämie im Monat.", RELATED, c.getId());
        return c;
    }

    /** Tank lock of the workshop for an own vehicle (once, TANK_LOCK). */
    @Transactional
    public TankLock buyTankLock(Savegame sg, String vehicleId) {
        FarmFacts f = facts.latest(sg).orElse(null);
        boolean own = f != null && f.assets() != null && f.assets().vehicles() != null
                && loaned.own(sg, f.assets().vehicles()).stream().anyMatch(v -> vehicleId.equals(v.uniqueId()));
        if (!own) {
            throw new NotFoundException("vehicle " + vehicleId);
        }
        if (locks.existsBySavegameAndVehicleId(sg, vehicleId)) {
            throw new BusinessRuleException("TANK_LOCK_EXISTS", "Diese Maschine hat schon ein Tankschloss.");
        }
        if (liquidity.available(sg) < cfg().getTankLockPrice()) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Der Kontostand reicht für das Tankschloss nicht.");
        }
        TankLock l = new TankLock();
        l.setSavegame(sg);
        l.setVehicleId(vehicleId);
        l.setBoughtGameTime(sg.getCurrentGameTime());
        locks.save(l);
        outbox.money(sg, -cfg().getTankLockPrice(), MoneyReason.TANK_LOCK, "Tankschloss", new Related(TANK_LOCK_RELATED, l.getId()));
        roles.ensure(sg, CharacterRole.WORKSHOP);
        return l;
    }

    public List<TankLock> locks(Savegame sg) {
        return locks.findBySavegameOrderByIdAsc(sg);
    }

    public List<DieselTheft> history(Savegame sg) {
        return thefts.findBySavegameOrderByIdDesc(sg);
    }
}
