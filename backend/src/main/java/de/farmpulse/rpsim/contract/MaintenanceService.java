package de.farmpulse.rpsim.contract;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-22 maintenance contract of the workshop. Monthly fee (MAINTENANCE_FEE, ContractBillingService); while the fee
 * is paid, the workshop repairs the most worn own vehicles at every month start (REPAIR_VEHICLE → the mod sets the
 * damage to 0 with Wearable:setDamageAmount). Without a contract the workshop sends occasional repair hints and one
 * unsolicited offer. Vehicle condition (0-100) comes from farm_facts.json.
 */
@Service
public class MaintenanceService {

    /** Related type of the repair instructions (the fee uses ContractBillingService.RELATED). */
    public static final String RELATED = "MAINTENANCE";

    private final ContractRepository contracts;
    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final ContractBillingService billing;
    private final FactsService facts;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public MaintenanceService(ContractRepository contracts, ServiceCaseRepository cases, SavegameRepository savegames,
                              ContractBillingService billing, FactsService facts, OutboxService outbox,
                              ServiceRoleService roles, NarrationRequestService narration, DiaryService diary,
                              RpsimProperties props, GameTime gameTime) {
        this.contracts = contracts;
        this.cases = cases;
        this.savegames = savegames;
        this.billing = billing;
        this.facts = facts;
        this.outbox = outbox;
        this.roles = roles;
        this.narration = narration;
        this.diary = diary;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Maintenance cfg() {
        return props.getFormulas().getMaintenance();
    }

    private List<BridgeDtos.Vehicle> vehicles(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        return f == null || f.assets() == null ? List.of() : f.assets().vehicles().stream()
                .filter(v -> v.uniqueId() != null && v.condition() != null).toList();
    }

    public long monthlyFee(Savegame sg) {
        double value = vehicles(sg).stream().mapToDouble(v -> v.value() == null ? 0 : v.value()).sum();
        return Math.max(cfg().getMinFee(), Math.round(value * cfg().getFeeRate() / 10.0) * 10);
    }

    public Optional<Contract> active(Savegame sg) {
        return contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.MAINTENANCE,
                List.of(ContractStatus.ACTIVE)).stream().findFirst();
    }

    // ------------------------------------------------------------------------------------------ contract

    /** Written offer of the workshop (replaces an older open offer). */
    @Transactional
    public Contract offer(Savegame sg) {
        if (active(sg).isPresent()) {
            throw new BusinessRuleException("MAINTENANCE_ACTIVE", "Es besteht bereits ein Wartungsvertrag.");
        }
        if (vehicles(sg).isEmpty()) {
            throw new BusinessRuleException("NO_VEHICLES", "Ohne eigene Fahrzeuge gibt es nichts zu warten.");
        }
        for (Contract old : contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.MAINTENANCE,
                List.of(ContractStatus.OFFERED))) {
            old.setStatus(ContractStatus.DECLINED);
            old.setEndReason("REPLACED");
        }
        Character workshop = roles.ensure(sg, CharacterRole.WORKSHOP);
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.MAINTENANCE);
        c.setStatus(ContractStatus.OFFERED);
        c.setCharacter(workshop);
        c.setMonthlyAmount(monthlyFee(sg));
        c.setOfferExpiresAtGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getOfferValidDays()));
        c.setCreatedAt(Instant.now());
        contracts.save(c);
        narration.request(sg, NarrationEventType.MAINTENANCE_OFFER).from(workshop)
                .facts(NarrationFacts.builder().put("monthlyFee", c.getMonthlyAmount()).put("vehicleCount", vehicles(sg).size())
                        .put("repairBelowCondition", Math.round(cfg().getRepairBelowCondition()))
                        .put("maxRepairsPerMonth", cfg().getMaxRepairsPerMonth())
                        .put("validDays", Math.round(cfg().getOfferValidDays())).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId())
                .formLink("/contracts?contract=" + c.getId()).submit();
        return c;
    }

    Contract own(Savegame sg, Long id) {
        return contracts.findById(id)
                .filter(c -> c.getSavegame().getId().equals(sg.getId()) && c.getKind() == ContractKind.MAINTENANCE)
                .orElseThrow(() -> new NotFoundException("contract " + id));
    }

    @Transactional
    public Contract accept(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        if (c.getOfferExpiresAtGameTime() != null && c.getOfferExpiresAtGameTime() < sg.getCurrentGameTime()) {
            throw new BusinessRuleException("OFFER_EXPIRED", "Das Angebot ist abgelaufen.");
        }
        billing.activate(sg, c);
        narration.request(sg, NarrationEventType.MAINTENANCE_CONFIRMED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("monthlyFee", c.getMonthlyAmount()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Wartungsvertrag abgeschlossen", c.getMonthlyAmount() + " € pro Monat bei "
                + c.getCharacter().getName() + ".", ContractBillingService.RELATED, c.getId());
        return c;
    }

    @Transactional
    public Contract decline(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        c.setStatus(ContractStatus.DECLINED);
        c.setEndReason("PLAYER");
        return c;
    }

    @Transactional
    public Contract cancel(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.ACTIVE) {
            throw new BusinessRuleException("NOT_ACTIVE", "Dieser Vertrag läuft nicht.");
        }
        end(sg, c, ContractStatus.CANCELLED, "TERMINATED_BY_PLAYER");
        return c;
    }

    private void end(Savegame sg, Contract c, ContractStatus status, String reason) {
        c.setStatus(status);
        c.setEndReason(reason);
        c.setEndsAtGameTime(sg.getCurrentGameTime());
        narration.request(sg, NarrationEventType.MAINTENANCE_CANCELLED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("reason", reason).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
    }

    @EventListener
    @Transactional
    public void onPaymentMissed(ContractEvents.PaymentMissed e) {
        Contract c = contracts.findById(e.contractId()).orElse(null);
        if (c == null || c.getKind() != ContractKind.MAINTENANCE || c.getStatus() != ContractStatus.ACTIVE) {
            return;
        }
        Savegame sg = c.getSavegame();
        if (e.missedPayments() >= cfg().getCancelAfterMissedPayments()) {
            end(sg, c, ContractStatus.ENDED, "FEE_MISSED");
            return;
        }
        narration.request(sg, NarrationEventType.MAINTENANCE_FEE_OVERDUE).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("monthlyFee", c.getMonthlyAmount()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
    }

    // ------------------------------------------------------------------------------------------ monthly service

    /** After the monthly billing (@Order 20 on the game time) - a paid-up contract repairs, otherwise hints. */
    @EventListener
    @Order(75)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Optional<Contract> c = active(sg);
        if (c.isPresent()) {
            if (!c.get().isPaymentOverdue() && c.get().getNextDueGameTime() != null
                    && c.get().getStartedAtGameTime() != null && c.get().getStartedAtGameTime() < sg.getCurrentGameTime()) {
                service(sg, c.get());
            }
            return;
        }
        hint(sg);
        maybeFirstOffer(sg);
    }

    /** Repairs the most worn vehicles below the threshold (at most maxRepairsPerMonth). */
    @Transactional
    public List<ServiceCase> service(Savegame sg, Contract c) {
        List<BridgeDtos.Vehicle> worn = vehicles(sg).stream()
                .filter(v -> v.condition() < cfg().getRepairBelowCondition())
                .sorted(Comparator.comparingDouble(BridgeDtos.Vehicle::condition))
                .limit(cfg().getMaxRepairsPerMonth()).toList();
        if (worn.isEmpty()) {
            return List.of();
        }
        List<ServiceCase> repaired = worn.stream().map(v -> {
            ServiceCase sc = newCase(sg, c.getCharacter(), v);
            sc.setContractId(c.getId());
            sc.setStatus(CaseStatus.SETTLED);
            sc.setResolution("INCLUDED");
            sc.setClosedAtGameTime(sg.getCurrentGameTime());
            cases.save(sc);
            outbox.repairVehicle(sg, v.uniqueId(), new Related(RELATED, sc.getId()));
            return sc;
        }).toList();
        long average = Math.round(worn.stream().mapToDouble(BridgeDtos.Vehicle::condition).average().orElse(0));
        narration.request(sg, NarrationEventType.MAINTENANCE_REPAIRED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("repairCount", repaired.size()).put("averageCondition", average).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Wartung erledigt", repaired.size() + " Maschinen instand gesetzt.",
                ContractBillingService.RELATED, c.getId());
        return repaired;
    }

    private ServiceCase newCase(Savegame sg, Character workshop, BridgeDtos.Vehicle v) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.REPAIR);
        sc.setCharacter(workshop);
        sc.setReference(v.uniqueId());
        sc.setQuantity((int) Math.round(v.condition()));
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setCreatedAt(Instant.now());
        return sc;
    }

    /** Without contract: a hint for the most worn vehicle below the hint threshold, at most every n months. */
    void hint(Savegame sg) {
        Optional<BridgeDtos.Vehicle> worst = vehicles(sg).stream()
                .filter(v -> v.condition() < cfg().getHintBelowCondition())
                .min(Comparator.comparingDouble(BridgeDtos.Vehicle::condition));
        if (worst.isEmpty()) {
            return;
        }
        long now = sg.getCurrentGameTime();
        boolean recent = cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.REPAIR)).stream()
                .filter(sc -> "HINT".equals(sc.getResolution())).findFirst()
                .map(sc -> gameTime.monthIndex(sg, now) - gameTime.monthIndex(sg, sc.getGameTime()) < cfg().getHintEveryMonths())
                .orElse(false);
        if (recent) {
            return;
        }
        Character workshop = roles.ensure(sg, CharacterRole.WORKSHOP);
        ServiceCase sc = newCase(sg, workshop, worst.get());
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("HINT");
        sc.setClosedAtGameTime(now);
        cases.save(sc);
        narration.request(sg, NarrationEventType.REPAIR_HINT).from(workshop)
                .facts(NarrationFacts.builder().put("condition", Math.round(worst.get().condition())).build())
                .category(CommunicationCategory.CONTRACT).related(InsuranceService.RELATED, sc.getId()).submit();
    }

    /** One unsolicited offer when a vehicle is worn and there never was a maintenance contract. */
    void maybeFirstOffer(Savegame sg) {
        boolean worn = vehicles(sg).stream().anyMatch(v -> v.condition() < cfg().getFirstOfferBelowCondition());
        if (!worn || !contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.MAINTENANCE,
                EnumSet.allOf(ContractStatus.class)).isEmpty()) {
            return;
        }
        offer(sg);
    }

    @EventListener
    @Order(75)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (Contract c : contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.MAINTENANCE,
                List.of(ContractStatus.OFFERED))) {
            if (c.getOfferExpiresAtGameTime() != null && c.getOfferExpiresAtGameTime() < now) {
                c.setStatus(ContractStatus.DECLINED);
                c.setEndReason("EXPIRED");
            }
        }
    }

    /** T-03: the mod could not repair (vehicle sold, not wearable ...) - the case is marked, no notice. */
    @Transactional
    public boolean onRepairFailed(Long caseId, String message) {
        ServiceCase sc = cases.findById(caseId).orElse(null);
        if (sc == null || sc.getKind() != CaseKind.REPAIR) {
            return false;
        }
        sc.setStatus(CaseStatus.EXPIRED);
        sc.setResolution("NOT_REPAIRED");
        return true;
    }
}
