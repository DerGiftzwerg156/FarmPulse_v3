package de.farmpulse.rpsim.dealer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MachineLoan;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.employee.AbsenceScale;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.MachineLoanRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.vehicle.VehicleTradeService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap vehicle dealer RFH-V1 (owner decisions 2026-10-15): "Vorführung anfragen" in the catalog. The dealer brings
 * the machine in its standard configuration with VEHICLE_SPAWN and price 0 (the way of R31-A2) to the shop place, free
 * for 2 days of a 30-day month (scaled to the month length, at least until 9:00 of the next game day), and takes it back
 * with VEHICLE_REMOVE. One dealer demo at a time (independent of R31-A2), each machine once per game year. No free shop
 * place: "Vorführung verschoben", a new attempt the next game day (at most spawn-max-attempts like R3-V2). In use or
 * coupled at the pick-up: a hint and a mail, a new attempt the next game day and 500 € per late day
 * (VEHICLE_DEMO_LATE_FEE). A demo machine that disappears costs its last game value (COMPENSATION) and trust like
 * R31-A2. While on the farm it is no asset of the farm ({@code LoanedVehicles}, machine_loan of kind DEALER_DEMO).
 */
@Service
public class DealerDemoService {

    public static final String RELATED = "DEALER_DEMO";

    /** A demo for the tablet: {@code lateReason} = the reason text of a late pick-up. */
    public record DemoView(long id, String status, String xmlFilename, String machine, Long untilGameTime,
                           int lateDays, String lateReason) {
    }

    /** The running demo, the last one and the machines demonstrated in this game year. */
    public record DemoState(DemoView running, DemoView last, List<String> demonstratedThisYear, long lateFee) {
    }

    private final MachineLoanRepository loans;
    private final SavegameRepository savegames;
    private final OutboxInstructionRepository instructions;
    private final MachineCatalogService catalog;
    private final MachineDealerService dealers;
    private final FactsService facts;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final TrustScoreService trust;
    private final AbsenceScale absence;
    private final RpsimProperties props;

    public DealerDemoService(MachineLoanRepository loans, SavegameRepository savegames,
                             OutboxInstructionRepository instructions, MachineCatalogService catalog,
                             MachineDealerService dealers, FactsService facts, OutboxService outbox,
                             NarrationRequestService narration, DiaryService diary, TrustScoreService trust,
                             AbsenceScale absence, RpsimProperties props) {
        this.loans = loans;
        this.savegames = savegames;
        this.instructions = instructions;
        this.catalog = catalog;
        this.dealers = dealers;
        this.facts = facts;
        this.outbox = outbox;
        this.narration = narration;
        this.diary = diary;
        this.trust = trust;
        this.absence = absence;
        this.props = props;
    }

    private RpsimProperties.MachineDealer cfg() {
        return props.getFormulas().getMachineDealer();
    }

    private List<MachineLoan> demos(Savegame sg) {
        return loans.findBySavegameOrderByIdDesc(sg).stream().filter(l -> MachineLoan.DEALER_DEMO.equals(l.getKind()))
                .toList();
    }

    public Optional<MachineLoan> running(Savegame sg) {
        return demos(sg).stream().filter(MachineLoan::running).findFirst();
    }

    private Integer year(Savegame sg) {
        return facts.latest(sg).map(f -> f.calendar() == null ? null : f.calendar().year()).orElse(null);
    }

    // ------------------------------------------------------------------------------------------ request

    /** "Vorführung anfragen": the standard configuration of a catalog entry. */
    @Transactional
    public MachineLoan request(Savegame sg, String xmlFilename) {
        if (running(sg).isPresent()) {
            throw new BusinessRuleException("DEMO_RUNNING", "Es läuft schon eine Vorführung.");
        }
        Integer year = year(sg);
        if (demonstrated(sg, year).contains(xmlFilename)) {
            throw new BusinessRuleException("DEMO_THIS_YEAR", "Diese Maschine wurde in diesem Spieljahr schon vorgeführt.");
        }
        BridgeDtos.CatalogItem item = catalog.requireItem(sg, xmlFilename);
        long listPrice = catalog.quote(sg, item, Map.of()).result().listPrice();
        Character dealer = dealers.open(sg);
        MachineLoan l = new MachineLoan();
        l.setSavegame(sg);
        l.setKind(MachineLoan.DEALER_DEMO);
        l.setStatus(MachineLoan.DELIVERING);
        l.setCharacter(dealer);
        l.setStoreXmlFilename(xmlFilename);
        l.setVehicleName(item.name() == null || item.name().isBlank() ? xmlFilename : item.name());
        l.setCategoryName(item.categoryName());
        l.setListPrice(listPrice);
        l.setDays(0);
        l.setGameYear(year);
        l.setCreatedGameTime(sg.getCurrentGameTime());
        loans.save(l);
        deliver(sg, l);
        return l;
    }

    /** Machines demonstrated by the dealer in the given game year (failed demos do not count). */
    Set<String> demonstrated(Savegame sg, Integer year) {
        return demos(sg).stream().filter(l -> !MachineLoan.FAILED.equals(l.getStatus()))
                .filter(l -> Objects.equals(l.getGameYear(), year)).map(MachineLoan::getStoreXmlFilename)
                .collect(Collectors.toSet());
    }

    private void deliver(Savegame sg, MachineLoan l) {
        l.setAttempts(l.getAttempts() + 1);
        l.setNextAttemptGameTime(null);
        outbox.loanSpawn(sg, l.getStoreXmlFilename(), 0, 0, 0, 0, new Related(RELATED, l.getId()));
    }

    // ------------------------------------------------------------------------------------------ course

    /** A new delivery attempt and the late pick-up (fee, new attempt) at the game day change. */
    @EventListener
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        MachineLoan l = running(sg).orElse(null);
        if (l == null) {
            return;
        }
        long now = sg.getCurrentGameTime();
        if (MachineLoan.DELIVERING.equals(l.getStatus())) {
            if (l.getNextAttemptGameTime() != null && l.getNextAttemptGameTime() <= now) {
                deliver(sg, l);
            }
            return;
        }
        if (!MachineLoan.ACTIVE.equals(l.getStatus()) || !observe(sg, l)) {
            return;
        }
        if (l.getEndReason() != null && l.getEndReason().startsWith("LATE")) {
            l.setLateDays(l.getLateDays() + 1);
            outbox.money(sg, -cfg().getDemoLateFee(), MoneyReason.VEHICLE_DEMO_LATE_FEE,
                    "Verzögerung Vorführmaschine " + l.getVehicleName(), new Related(RELATED, l.getId()));
            pickUp(sg, l);
        }
    }

    /** With every export: a machine that disappeared, and the pick-up when the demo time is over. */
    @EventListener
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        savegames.findById(e.savegameId()).ifPresent(this::tick);
    }

    @Transactional
    public void tick(Savegame sg) {
        MachineLoan l = running(sg).orElse(null);
        if (l == null || !MachineLoan.ACTIVE.equals(l.getStatus()) || !observe(sg, l)) {
            return;
        }
        boolean late = l.getEndReason() != null && l.getEndReason().startsWith("LATE");
        if (!late && l.getEndsGameTime() != null && sg.getCurrentGameTime() >= l.getEndsGameTime()) {
            pickUp(sg, l);
        }
    }

    /** Keeps the last value; false when the machine disappeared (claim and trust like R31-A2). */
    boolean observe(Savegame sg, MachineLoan l) {
        BridgeDtos.FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.assets() == null || f.assets().vehicles() == null || l.getVehicleId() == null) {
            return true;
        }
        Optional<BridgeDtos.Vehicle> v = f.assets().vehicles().stream()
                .filter(x -> x != null && l.getVehicleId().equals(x.uniqueId())).findFirst();
        if (v.isPresent()) {
            if (v.get().value() != null) {
                l.setLastValue(Math.round(v.get().value()));
            }
            return true;
        }
        boolean pending = instructions.findBySavegameOrderByIdAsc(sg).stream()
                .anyMatch(o -> RELATED.equals(o.getRelatedEntityType()) && l.getId().equals(o.getRelatedEntityId())
                        && o.getType() == InstructionType.VEHICLE_SPAWN && o.getStatus() == InstructionStatus.PENDING);
        if (pending || f.gameTime() == null || l.getDeliveredGameTime() == null || f.gameTime() <= l.getDeliveredGameTime()) {
            return true;
        }
        lost(sg, l);
        return false;
    }

    private void lost(Savegame sg, MachineLoan l) {
        long claim = l.getLastValue() == null
                ? Math.round(l.getListPrice() * props.getFormulas().getUsedVehicle().getMinPriceShare()) : l.getLastValue();
        l.setStatus(MachineLoan.LOST);
        l.setEndReason("DISAPPEARED");
        l.setCompensation(claim);
        l.setClosedGameTime(sg.getCurrentGameTime());
        outbox.money(sg, -claim, MoneyReason.COMPENSATION, "Ersatz für " + l.getVehicleName(), new Related(RELATED, l.getId()));
        trust.recordEvent(l.getCharacter(), props.getFormulas().getMachineLoan().getLostTrustDelta(),
                TrustReason.DEALER_DEMO_LOST, l.getVehicleName());
        narration.request(sg, NarrationEventType.MACHINE_LOAN_LOST).from(l.getCharacter())
                .facts(NarrationFacts.builder().put("vehicleName", l.getVehicleName()).put("kind", l.getKind())
                        .put("claim", claim).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, l.getId()).submit();
        diary.addAuto(sg, "NEGOTIATION", l.getVehicleName() + " nicht zurückgegeben", "Die Vorführmaschine ist vom Hof "
                + "verschwunden. " + l.getCharacter().getName() + " verlangt " + claim + " € Ersatz.", RELATED, l.getId());
    }

    private void pickUp(Savegame sg, MachineLoan l) {
        l.setStatus(MachineLoan.RETURNING);
        if (l.getVehicleId() == null) {
            ended(sg, l);
            return;
        }
        outbox.vehicleRemove(sg, l.getVehicleId(), new Related(RELATED, l.getId()));
    }

    // ------------------------------------------------------------------------------------------ acks

    /** Delivered (VEHICLE_SPAWN) or taken back (VEHICLE_REMOVE). */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        MachineLoan l = loans.findById(e.relatedId()).orElse(null);
        OutboxInstruction ins = instructions.findByInstructionId(e.instructionId()).orElse(null);
        if (l == null || ins == null) {
            return;
        }
        Savegame sg = l.getSavegame();
        long now = sg.getCurrentGameTime();
        if (ins.getType() == InstructionType.VEHICLE_SPAWN && MachineLoan.DELIVERING.equals(l.getStatus())) {
            Object id = e.result() == null ? null : e.result().get("vehicleId");
            l.setVehicleId(id == null ? null : id.toString());
            l.setStatus(MachineLoan.ACTIVE);
            l.setDeliveredGameTime(now);
            l.setEndsGameTime(DeliveryTime.at(now, cfg().getDemoDays(), absence.daysPerPeriod(sg),
                    props.getFormulas().getAbsenceScaling(), cfg().getEarliestDeliveryHour()));
            narration.request(sg, NarrationEventType.DEALER_DEMO_DELIVERED).from(l.getCharacter())
                    .facts(NarrationFacts.builder().put("machine", l.getVehicleName())
                            .put("until", GameTimeText.of(l.getEndsGameTime())).build())
                    .category(CommunicationCategory.NEGOTIATION).related(RELATED, l.getId())
                    .formLink("/haendler/bestellungen").submit();
            diary.addAuto(sg, "NEGOTIATION", "Vorführmaschine: " + l.getVehicleName(), l.getVehicleName()
                    + " vom Händler auf dem Shop-Platz, kostenlos bis " + GameTimeText.of(l.getEndsGameTime()) + ".",
                    RELATED, l.getId());
        } else if (ins.getType() == InstructionType.VEHICLE_REMOVE && MachineLoan.RETURNING.equals(l.getStatus())) {
            ended(sg, l);
        }
    }

    private void ended(Savegame sg, MachineLoan l) {
        l.setStatus(MachineLoan.RETURNED);
        l.setClosedGameTime(sg.getCurrentGameTime());
        narration.request(sg, NarrationEventType.DEALER_DEMO_ENDED).from(l.getCharacter())
                .facts(NarrationFacts.builder().put("machine", l.getVehicleName()).put("lateDays", l.getLateDays()).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, l.getId())
                .formLink("/haendler/bestellungen").submit();
        diary.addAuto(sg, "NEGOTIATION", "Vorführung beendet: " + l.getVehicleName(), "Der Händler hat die Maschine "
                + "wieder abgeholt" + (l.getLateDays() > 0 ? ", " + l.getLateDays() + " Tag(e) zu spät." : "."),
                RELATED, l.getId());
    }

    /**
     * FailedInstructionService: delivery without a free shop place = "Vorführung verschoben" and a new attempt the next
     * game day (at most spawn-max-attempts, then the demo fails); pick-up in use or coupled = hint, mail, new attempt and
     * the late fee the next game day; a machine that is gone = disappeared.
     */
    @Transactional
    public boolean onInstructionFailed(Long id, InstructionType type, String message) {
        MachineLoan l = loans.findById(id).orElse(null);
        if (l == null || !l.running()) {
            return false;
        }
        Savegame sg = l.getSavegame();
        long now = sg.getCurrentGameTime();
        String reason = VehicleTradeService.reasonOf(message);
        if (type == InstructionType.VEHICLE_SPAWN && MachineLoan.DELIVERING.equals(l.getStatus())) {
            if ("NO_SPACE".equals(reason) && l.getAttempts() < props.getFormulas().getUsedVehicle().getSpawnMaxAttempts()) {
                l.setNextAttemptGameTime((GameTime.dayIndex(now) + 1) * GameTime.MS_PER_DAY); // the next game day
                if (l.getAttempts() == 1) {
                    narration.request(sg, NarrationEventType.DEALER_DEMO_POSTPONED).from(l.getCharacter())
                            .facts(NarrationFacts.builder().put("machine", l.getVehicleName()).build())
                            .category(CommunicationCategory.NEGOTIATION).related(RELATED, l.getId()).submit();
                }
                hint(sg, l, "FarmPulse: Kein freier Shop-Platz für " + l.getVehicleName() + " – bitte Platz freiräumen.");
                return true;
            }
            l.setStatus(MachineLoan.FAILED);
            l.setEndReason(reason);
            l.setClosedGameTime(now);
            narration.request(sg, NarrationEventType.MACHINE_LOAN_FAILED).from(l.getCharacter())
                    .facts(NarrationFacts.builder().put("vehicleName", l.getVehicleName()).put("kind", l.getKind())
                            .put("reason", reason).build())
                    .category(CommunicationCategory.NEGOTIATION).related(RELATED, l.getId()).submit();
            return true;
        }
        if (type == InstructionType.VEHICLE_REMOVE && MachineLoan.RETURNING.equals(l.getStatus())) {
            if ("VEHICLE_NOT_FOUND".equals(reason) || "NOT_OWN_VEHICLE".equals(reason)) {
                lost(sg, l);
                return true;
            }
            boolean first = l.getEndReason() == null;
            l.setStatus(MachineLoan.ACTIVE);
            l.setEndReason("LATE:" + reason);
            hint(sg, l, "VEHICLE_ATTACHED".equals(reason)
                    ? "FarmPulse: Bitte " + l.getVehicleName() + " abkoppeln – der Händler holt sie morgen ab."
                    : "FarmPulse: Bitte " + l.getVehicleName() + " abstellen – der Händler holt sie morgen ab.");
            if (first) {
                narration.request(sg, NarrationEventType.DEALER_DEMO_LATE).from(l.getCharacter())
                        .facts(NarrationFacts.builder().put("machine", l.getVehicleName())
                                .put("reason", "VEHICLE_ATTACHED".equals(reason) ? "er ist angekoppelt" : "er ist in Benutzung")
                                .put("fee", cfg().getDemoLateFee()).build())
                        .category(CommunicationCategory.NEGOTIATION).related(RELATED, l.getId())
                        .formLink("/haendler/bestellungen").submit();
            }
            return true;
        }
        return false;
    }

    private void hint(Savegame sg, MachineLoan l, String text) {
        outbox.notification(sg, text, "WARNING", sg.getCurrentGameTime() + GameTime.days(1), new Related(RELATED, l.getId()));
    }

    // ------------------------------------------------------------------------------------------ view

    @Transactional(readOnly = true)
    public DemoState state(Savegame sg) {
        List<MachineLoan> all = demos(sg);
        DemoView running = all.stream().filter(MachineLoan::running).findFirst().map(this::view).orElse(null);
        DemoView last = all.stream().filter(l -> MachineLoan.RETURNED.equals(l.getStatus())).findFirst().map(this::view)
                .orElse(null);
        return new DemoState(running, last, demonstrated(sg, year(sg)).stream().sorted().toList(), cfg().getDemoLateFee());
    }

    private DemoView view(MachineLoan l) {
        String late = null;
        if (l.getEndReason() != null && l.getEndReason().startsWith("LATE:")) {
            late = l.getEndReason().endsWith("VEHICLE_ATTACHED") ? l.getVehicleName() + " ist angekoppelt"
                    : l.getVehicleName() + " ist in Benutzung";
        }
        return new DemoView(l.getId(), l.getStatus(), l.getStoreXmlFilename(), l.getVehicleName(), l.getEndsGameTime(),
                l.getLateDays(), late);
    }
}
