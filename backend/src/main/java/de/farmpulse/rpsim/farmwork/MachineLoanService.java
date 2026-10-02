package de.farmpulse.rpsim.farmwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Initiator;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MachineLoan;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationDirection;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.domain.VehicleDeal;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.neighbor.NeighborService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.MachineLoanRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.VehicleDealRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.vehicle.VehicleTradeService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-A2: borrowed machine of a neighbour and demo machine of the workshop (owner decisions in
 * QUESTIONS.md). At most one borrowed or demo machine at a time.
 * <ul>
 *   <li>Loan: the neighbour offers machines of the shop catalog (R3-V1) in the categories of his role; the player picks
 *   one and 1-5 game days. The rent for all days must be available. VEHICLE_SPAWN with price 0 brings the machine (age,
 *   hours and wear like a used machine of R3-V2); the rent is booked as MACHINE_RENT every game day from the delivery.
 *   After the agreed days VEHICLE_REMOVE takes it back; in use or coupled: a reminder in the game, a new attempt the
 *   next game day and rent + surcharge per late day. A rent that cannot be booked ends the loan at once. Condition lost
 *   costs a compensation (COMPENSATION) and - from a threshold - trust.</li>
 *   <li>Demo: the player asks the workshop, or the workshop offers one at a month start (accept / decline). New machine,
 *   free for 1-2 game days, then a purchase offer (list price - discount) negotiated like R3-V2; on an agreement only
 *   the price is booked (VEHICLE_PURCHASE) and the machine stays, otherwise VEHICLE_REMOVE.</li>
 *   <li>A borrowed or demo machine that disappears without being returned (sold in the game shop) costs its last game
 *   value as a claim and trust.</li>
 * </ul>
 * While on the farm the machine is no asset of the farm ({@link LoanedVehicles}).
 */
@Service
public class MachineLoanService {

    public static final String RELATED = "MACHINE_LOAN";

    /** A machine of the catalog to choose from; {@code dailyRent} 0 for a demo. */
    public record Choice(String storeXmlFilename, String name, String categoryName, long listPrice, long dailyRent) {
    }

    private final MachineLoanRepository loans;
    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final ServiceCaseRepository cases;
    private final OutboxInstructionRepository instructions;
    private final VehicleDealRepository deals;
    private final NegotiationEngine engine;
    private final FactsService facts;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final NeighborService neighbors;
    private final ServiceRoleService roles;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;

    public MachineLoanService(MachineLoanRepository loans, SavegameRepository savegames, CharacterRepository characters,
                              ServiceCaseRepository cases, OutboxInstructionRepository instructions,
                              VehicleDealRepository deals, NegotiationEngine engine, FactsService facts,
                              LiquidityService liquidity, OutboxService outbox, NeighborService neighbors,
                              ServiceRoleService roles, TrustScoreService trust, NarrationRequestService narration,
                              DiaryService diary, RandomSource random, RpsimProperties props) {
        this.loans = loans;
        this.savegames = savegames;
        this.characters = characters;
        this.cases = cases;
        this.instructions = instructions;
        this.deals = deals;
        this.engine = engine;
        this.facts = facts;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.neighbors = neighbors;
        this.roles = roles;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.MachineLoan cfg() {
        return props.getFormulas().getMachineLoan();
    }

    private RpsimProperties.UsedVehicle used() {
        return props.getFormulas().getUsedVehicle();
    }

    // ------------------------------------------------------------------------------------------ formulas

    /** Rent per game day: list price x share, good trust = cheaper (neighbor-trade trust divisor and cap). */
    public long dailyRent(long listPrice, Character lender) {
        double adj = lender == null ? 0 : neighbors.trustAdjustment(trust.getCurrentTrust(lender));
        return Math.round(listPrice * cfg().getRentSharePerDay() * (1 - adj));
    }

    /** Damage compensation: condition points lost / 100 x list price x share; 0 without a loss. */
    public long compensation(MachineLoan l) {
        if (l.getStartCondition() == null || l.getLastCondition() == null) {
            return 0;
        }
        double lost = l.getStartCondition() - l.getLastCondition();
        return lost <= 0 ? 0 : Math.round(lost / 100.0 * l.getListPrice() * cfg().getCompensationShare());
    }

    // ------------------------------------------------------------------------------------------ choices

    /** Catalog entries with a known list price in the range of R3-V2. */
    private List<BridgeDtos.StoreVehicle> catalog(Savegame sg) {
        return facts.marketContext(sg).map(BridgeDtos.MarketContext::storeVehicles).orElse(List.of()).stream()
                .filter(v -> v != null && v.xmlFilename() != null && !v.xmlFilename().isBlank() && v.price() != null
                        && v.price() >= used().getMinListPrice() && v.price() <= used().getMaxListPrice())
                .toList();
    }

    /** Up to {@code choices} entries, the same for one game day (seeded by the day and the lender). */
    private List<BridgeDtos.StoreVehicle> pick(Savegame sg, List<BridgeDtos.StoreVehicle> list, long salt) {
        List<BridgeDtos.StoreVehicle> copy = new ArrayList<>(list);
        copy.sort((a, b) -> a.xmlFilename().compareTo(b.xmlFilename()));
        long seed = GameTime.dayIndex(sg.getCurrentGameTime()) * 1_000_003L + salt;
        Collections.shuffle(copy, new java.util.Random(seed));
        return copy.subList(0, Math.min(cfg().getChoices(), copy.size()));
    }

    /** "Maschine leihen": machines of the categories of the neighbour's role. */
    @Transactional
    public List<Choice> loanChoices(Savegame sg, Long neighborId) {
        Character n = neighbor(sg, neighborId);
        List<String> categories = cfg().getRoleCategories().getOrDefault(neighbors.ensureRole(n), List.of());
        List<BridgeDtos.StoreVehicle> list = catalog(sg).stream().filter(v -> v.categoryName() != null
                && categories.contains(v.categoryName().toUpperCase())).toList();
        return pick(sg, list, n.getId()).stream().map(v -> new Choice(v.xmlFilename(), name(v), v.categoryName(),
                Math.round(v.price()), dailyRent(Math.round(v.price()), n))).toList();
    }

    /** "Vorführung anfragen": motorised machines of the catalog (a machine without the flag counts as motorised). */
    @Transactional
    public List<Choice> demoChoices(Savegame sg) {
        List<BridgeDtos.StoreVehicle> list = catalog(sg).stream().filter(v -> !Boolean.FALSE.equals(v.motorized())).toList();
        return pick(sg, list, 0).stream().map(v -> new Choice(v.xmlFilename(), name(v), v.categoryName(),
                Math.round(v.price()), 0)).toList();
    }

    private static String name(BridgeDtos.StoreVehicle v) {
        return v.name() != null && !v.name().isBlank() ? v.name() : v.xmlFilename();
    }

    // ------------------------------------------------------------------------------------------ start

    /** The player borrows one of the neighbour's machines for {@code days} game days. */
    @Transactional
    public MachineLoan borrow(Savegame sg, Long neighborId, String storeXmlFilename, int days) {
        requireEnabled();
        Character n = neighbor(sg, neighborId);
        if (days < cfg().getDaysMin() || days > cfg().getDaysMax()) {
            throw new BusinessRuleException("LOAN_DAYS", "Bitte " + cfg().getDaysMin() + " bis " + cfg().getDaysMax()
                    + " Spieltage wählen.");
        }
        Choice c = loanChoices(sg, neighborId).stream().filter(x -> x.storeXmlFilename().equals(storeXmlFilename))
                .findFirst().orElseThrow(() -> new BusinessRuleException("LOAN_CHOICE", n.getName()
                        + " hat diese Maschine heute nicht zu verleihen."));
        requireNoRunningLoan(sg);
        long total = c.dailyRent() * days;
        if (liquidity.available(sg) < total) {
            throw new BusinessRuleException("LOAN_FUNDS", "Die Miete für " + days + " Tage (" + total
                    + " €) ist auf deinem Konto nicht verfügbar.");
        }
        int age = random.intBetween(used().getAgeMonthsMin(), used().getAgeMonthsMax());
        double lifetime = lifetime(sg, storeXmlFilename);
        int hours = VehicleTradeService.hoursFor(random.uniform(used().getHourFactorMin(), used().getHourFactorMax()),
                lifetime, motorized(sg, storeXmlFilename), used());
        double damage = round2(random.uniform(used().getDamageMin(), used().getDamageMax()));
        double wear = round2(random.uniform(used().getWearMin(), used().getWearMax()));
        MachineLoan l = newLoan(sg, MachineLoan.LOAN, n, c, days, age, hours, damage, wear);
        l.setDailyRent(c.dailyRent());
        return deliver(sg, l);
    }

    /** The player asks the workshop for a demo of one of the offered machines. */
    @Transactional
    public MachineLoan requestDemo(Savegame sg, String storeXmlFilename) {
        requireEnabled();
        Choice c = demoChoices(sg).stream().filter(x -> x.storeXmlFilename().equals(storeXmlFilename)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("DEMO_CHOICE", "Die Werkstatt hat diese Maschine heute "
                        + "nicht zur Vorführung."));
        return startDemo(sg, c);
    }

    private MachineLoan startDemo(Savegame sg, Choice c) {
        requireNoRunningLoan(sg);
        int days = random.intBetween(cfg().getDemoDaysMin(), cfg().getDemoDaysMax());
        MachineLoan l = newLoan(sg, MachineLoan.DEMO, roles.ensure(sg, CharacterRole.WORKSHOP), c, days, 0, 0, 0, 0);
        return deliver(sg, l);
    }

    private MachineLoan newLoan(Savegame sg, String kind, Character lender, Choice c, int days, int age, int hours,
                                double damage, double wear) {
        MachineLoan l = new MachineLoan();
        l.setSavegame(sg);
        l.setKind(kind);
        l.setStatus(MachineLoan.DELIVERING);
        l.setCharacter(lender);
        l.setStoreXmlFilename(c.storeXmlFilename());
        l.setVehicleName(c.name());
        l.setCategoryName(c.categoryName());
        l.setListPrice(c.listPrice());
        l.setAgeMonths(age);
        l.setOperatingHours(hours);
        l.setDamage(damage);
        l.setWear(wear);
        l.setDays(days);
        l.setCreatedGameTime(sg.getCurrentGameTime());
        return loans.save(l);
    }

    private MachineLoan deliver(Savegame sg, MachineLoan l) {
        l.setAttempts(l.getAttempts() + 1);
        l.setNextAttemptGameTime(null);
        outbox.loanSpawn(sg, l.getStoreXmlFilename(), l.getAgeMonths(), l.getOperatingHours(), l.getDamage(), l.getWear(),
                new Related(RELATED, l.getId()));
        return l;
    }

    private void requireEnabled() {
        if (!cfg().isEnabled()) {
            throw new BusinessRuleException("LOAN_OFF", "Leihen und Vorführungen sind ausgeschaltet.");
        }
    }

    private void requireNoRunningLoan(Savegame sg) {
        if (running(sg).isPresent()) {
            throw new BusinessRuleException("LOAN_RUNNING", "Es steht schon eine Leih- oder Vorführmaschine auf dem Hof.");
        }
    }

    public Optional<MachineLoan> running(Savegame sg) {
        return loans.findBySavegameOrderByIdDesc(sg).stream().filter(MachineLoan::running).findFirst();
    }

    // ------------------------------------------------------------------------------------------ demo offer

    /** Month start: the workshop offers a demo on its own (accept / decline), at most one open offer. */
    @EventListener
    @Order(83)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        if (!cfg().isEnabled()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (running(sg).isPresent() || openDemoOffer(sg).isPresent() || !random.chance(cfg().getDemoOfferProbability())) {
            return;
        }
        List<Choice> choices = demoChoices(sg);
        if (choices.isEmpty()) {
            return;
        }
        offerDemo(sg, random.pick(choices));
    }

    ServiceCase offerDemo(Savegame sg, Choice c) {
        Character workshop = roles.ensure(sg, CharacterRole.WORKSHOP);
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.MACHINE_DEMO_OFFER);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(workshop);
        sc.setReference(c.storeXmlFilename());
        sc.setTitle(c.name());
        sc.setOfferAmount(c.listPrice());
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getDemoAnswerDays()));
        sc.setCreatedAt(java.time.Instant.now());
        cases.save(sc);
        narration.request(sg, NarrationEventType.MACHINE_DEMO_OFFER).from(workshop)
                .facts(NarrationFacts.builder().put("vehicleName", c.name()).put("listPrice", c.listPrice())
                        .put("daysMin", cfg().getDemoDaysMin()).put("daysMax", cfg().getDemoDaysMax())
                        .put("answerDays", Math.round(cfg().getDemoAnswerDays())).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, sc.getId()).formLink("/werkstatt?case=" + sc.getId())
                .submit();
        return sc;
    }

    Optional<ServiceCase> openDemoOffer(Savegame sg) {
        return cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER).stream()
                .filter(c -> c.getKind() == CaseKind.MACHINE_DEMO_OFFER).findFirst();
    }

    /** "Vorführung annehmen" (ContractActions): the workshop brings the offered machine. */
    @Transactional
    public ServiceCase acceptDemoOffer(Savegame sg, Long caseId) {
        ServiceCase sc = demoOffer(sg, caseId);
        FarmFacts f = facts.latest(sg).orElse(null);
        BridgeDtos.StoreVehicle item = catalog(sg).stream().filter(v -> v.xmlFilename().equals(sc.getReference()))
                .findFirst().orElseThrow(() -> new BusinessRuleException("DEMO_CHOICE", "Diese Maschine gibt es im Shop "
                        + "nicht mehr."));
        if (f == null) {
            throw new BusinessRuleException("DEMO_NO_FACTS", "Noch keine Daten aus dem Spiel.");
        }
        startDemo(sg, new Choice(item.xmlFilename(), name(item), item.categoryName(), Math.round(item.price()), 0));
        close(sc, CaseStatus.SETTLED, "ACCEPTED");
        return sc;
    }

    @Transactional
    public ServiceCase declineDemoOffer(Savegame sg, Long caseId) {
        ServiceCase sc = demoOffer(sg, caseId);
        close(sc, CaseStatus.DECLINED, "PLAYER");
        return sc;
    }

    private ServiceCase demoOffer(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (sc.getKind() != CaseKind.MACHINE_DEMO_OFFER || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieses Angebot ist nicht mehr offen.");
        }
        return sc;
    }

    private static void close(ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sc.getSavegame().getCurrentGameTime());
    }

    // ------------------------------------------------------------------------------------------ daily

    /**
     * Daily: a new delivery after NO_SPACE, the last condition and value of the machine, the rent of the next day, the
     * return at the end, the purchase offer after a demo, and a machine that disappeared.
     */
    @EventListener
    @Order(83)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() == CaseKind.MACHINE_DEMO_OFFER && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() < now) {
                close(sc, CaseStatus.EXPIRED, "NO_ANSWER");
            }
        }
        MachineLoan l = running(sg).orElse(null);
        if (l == null) {
            return;
        }
        if (MachineLoan.DELIVERING.equals(l.getStatus())) {
            if (l.getNextAttemptGameTime() != null && l.getNextAttemptGameTime() <= now) {
                deliver(sg, l);
            }
            return;
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        if (!observe(sg, l, f)) {
            return; // disappeared
        }
        if (MachineLoan.PURCHASE_OFFER.equals(l.getStatus())) {
            VehicleDeal d = l.getVehicleDealId() == null ? null : deals.findById(l.getVehicleDealId()).orElse(null);
            if (d == null || VehicleDeal.ENDED.equals(d.getStatus()) || VehicleDeal.FAILED.equals(d.getStatus())) {
                giveBack(sg, l, "NOT_BOUGHT");
            }
            return;
        }
        if (!MachineLoan.ACTIVE.equals(l.getStatus())) {
            return;
        }
        if ("RENT_MISSED".equals(l.getEndReason()) || "NOT_BOUGHT".equals(l.getEndReason())) {
            giveBack(sg, l, l.getEndReason()); // a failed return of a recalled loan or an unsold demo: try again
            return;
        }
        if (MachineLoan.LOAN.equals(l.getKind()) && l.getRentDaysBooked() < l.getDays()) {
            bookRent(sg, l, l.getDailyRent(), false);
            return;
        }
        if (l.getEndsGameTime() != null && now < l.getEndsGameTime()) {
            return;
        }
        if (MachineLoan.DEMO.equals(l.getKind())) {
            purchaseOffer(sg, l);
            return;
        }
        if (l.getEndReason() != null && l.getEndReason().startsWith("LATE")) {
            // a failed return yesterday: today costs rent + surcharge, then a new attempt
            l.setLateDays(l.getLateDays() + 1);
            bookRent(sg, l, Math.round(l.getDailyRent() * (1 + cfg().getLateSurchargeShare())), true);
        }
        giveBack(sg, l, "END");
    }

    /**
     * Keeps condition and value of the latest export; false when the machine disappeared (claim). A machine is only
     * missing when an export after the delivery lacks it and no instruction of the loan is pending (a reload without
     * saving re-sends the delivery: its VEHICLE_SPAWN is pending again).
     */
    boolean observe(Savegame sg, MachineLoan l, FarmFacts f) {
        if (f == null || f.assets() == null || f.assets().vehicles() == null || l.getVehicleId() == null) {
            return true;
        }
        Optional<BridgeDtos.Vehicle> v = f.assets().vehicles().stream()
                .filter(x -> x != null && l.getVehicleId().equals(x.uniqueId())).findFirst();
        if (v.isPresent()) {
            if (v.get().condition() != null) {
                l.setLastCondition(v.get().condition());
            }
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
        long claim = l.getLastValue() == null ? Math.round(l.getListPrice() * used().getMinPriceShare()) : l.getLastValue();
        l.setStatus(MachineLoan.LOST);
        l.setEndReason("DISAPPEARED");
        l.setCompensation(claim);
        l.setClosedGameTime(sg.getCurrentGameTime());
        outbox.money(sg, -claim, MoneyReason.COMPENSATION, "Ersatz für " + l.getVehicleName(), new Related(RELATED, l.getId()));
        trust.recordEvent(l.getCharacter(), cfg().getLostTrustDelta(), TrustReason.MACHINE_LOAN_LOST, l.getVehicleName());
        narration.request(sg, NarrationEventType.MACHINE_LOAN_LOST).from(l.getCharacter())
                .facts(facts(l).put("claim", claim).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, l.getId()).submit();
        diary.addAuto(sg, "FARM_WORK", l.getVehicleName() + " nicht zurückgegeben", "Die "
                + (MachineLoan.DEMO.equals(l.getKind()) ? "Vorführmaschine" : "Leihmaschine") + " ist vom Hof verschwunden. "
                + l.getCharacter().getName() + " verlangt " + claim + " € Ersatz.", RELATED, l.getId());
    }

    private void bookRent(Savegame sg, MachineLoan l, long amount, boolean late) {
        if (!late) {
            l.setRentDaysBooked(l.getRentDaysBooked() + 1);
        }
        if (amount > 0) {
            outbox.money(sg, -amount, MoneyReason.MACHINE_RENT, (late ? "Verspätung " : "Miete ") + l.getVehicleName(),
                    new Related(RELATED, l.getId()));
        }
    }

    private void giveBack(Savegame sg, MachineLoan l, String reason) {
        l.setStatus(MachineLoan.RETURNING);
        if (!"END".equals(reason) || l.getEndReason() == null) {
            l.setEndReason(reason);
        }
        if (l.getVehicleId() == null) {
            finish(sg, l, MachineLoan.RETURNED);
            return;
        }
        outbox.vehicleRemove(sg, l.getVehicleId(), new Related(RELATED, l.getId()));
    }

    private void purchaseOffer(Savegame sg, MachineLoan l) {
        long price = Math.round(l.getListPrice() * (1 - cfg().getDemoDiscount()));
        long now = sg.getCurrentGameTime();
        VehicleDeal d = new VehicleDeal();
        d.setSavegame(sg);
        d.setDirection(VehicleDeal.BUY);
        d.setStatus(VehicleDeal.OPEN);
        d.setSellerKind(VehicleDeal.WORKSHOP);
        d.setCharacter(l.getCharacter());
        d.setStoreXmlFilename(l.getStoreXmlFilename());
        d.setVehicleName(l.getVehicleName());
        d.setCategoryName(l.getCategoryName());
        d.setListPrice(l.getListPrice());
        d.setAgeMonths(0);
        d.setOperatingHours(0);
        d.setDamage(0.0);
        d.setWear(0.0);
        d.setGamePrice(l.getListPrice());
        d.setBasePrice(price);
        d.setVehicleId(l.getVehicleId());
        d.setDemoLoanId(l.getId());
        d.setCreatedGameTime(now);
        deals.save(d);
        Negotiation n = engine.openVehicle(sg, NegotiationKind.DIRECT, NegotiationDirection.PLAYER_BUYS,
                Initiator.CHARACTER, d.getId(), price, l.getCharacter(), now + GameTime.days(used().getNegotiationDays()),
                null, null);
        engine.counterpartOffer(n, price);
        l.setStatus(MachineLoan.PURCHASE_OFFER);
        l.setVehicleDealId(d.getId());
        narration.request(sg, NarrationEventType.MACHINE_DEMO_PURCHASE_OFFER).from(l.getCharacter())
                .facts(facts(l).put("price", price).put("listPrice", l.getListPrice())
                        .put("validDays", Math.round(used().getNegotiationDays())).build())
                .category(CommunicationCategory.NEGOTIATION).related(NegotiationEngine.RELATED, n.getId())
                .formLink("/werkstatt?negotiation=" + n.getId()).submit();
    }

    // ------------------------------------------------------------------------------------------ acks

    /** Delivered (VEHICLE_SPAWN), taken back (VEHICLE_REMOVE) or the demo bought (MONEY_TRANSACTION VEHICLE_PURCHASE). */
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
            l.setEndsGameTime(now + GameTime.days(l.getDays()));
            l.setStartCondition((double) Math.round((1 - l.getDamage()) * 100));
            l.setLastCondition(l.getStartCondition());
            if (MachineLoan.LOAN.equals(l.getKind())) {
                bookRent(sg, l, l.getDailyRent(), false); // the first day
            }
            narration.request(sg, NarrationEventType.MACHINE_LOAN_DELIVERED).from(l.getCharacter())
                    .facts(facts(l).build()).category(CommunicationCategory.CONTRACT).related(RELATED, l.getId()).submit();
            diary.addAuto(sg, "FARM_WORK", (MachineLoan.DEMO.equals(l.getKind()) ? "Vorführmaschine: " : "Leihmaschine: ")
                    + l.getVehicleName(), l.getVehicleName() + " von " + l.getCharacter().getName() + " für " + l.getDays()
                    + (l.getDays() == 1 ? " Spieltag" : " Spieltage") + (MachineLoan.LOAN.equals(l.getKind())
                    ? ", " + l.getDailyRent() + " € Miete je Tag." : ", kostenlos."), RELATED, l.getId());
        } else if (ins.getType() == InstructionType.VEHICLE_REMOVE && MachineLoan.RETURNING.equals(l.getStatus())) {
            finish(sg, l, MachineLoan.RETURNED);
        } else if (ins.getType() == InstructionType.MONEY_TRANSACTION && MachineLoan.PURCHASE_OFFER.equals(l.getStatus())
                && ins.getPayloadJson() != null && ins.getPayloadJson().contains(MoneyReason.VEHICLE_PURCHASE.name())) {
            VehicleDeal d = l.getVehicleDealId() == null ? null : deals.findById(l.getVehicleDealId()).orElse(null);
            if (d != null) {
                d.setStatus(VehicleDeal.DONE);
                d.setClosedGameTime(now);
            }
            l.setStatus(MachineLoan.PURCHASED);
            l.setEndReason("BOUGHT");
            l.setClosedGameTime(now);
            diary.addAuto(sg, "NEGOTIATION", l.getVehicleName() + " gekauft", "Die Vorführmaschine bleibt auf dem Hof"
                    + (d == null || d.getFinalPrice() == null ? "." : " – " + d.getFinalPrice() + " €."), RELATED, l.getId());
        }
    }

    /** Returned: damage compensation of a borrowed machine, mail, diary. */
    private void finish(Savegame sg, MachineLoan l, String status) {
        l.setStatus(status);
        l.setClosedGameTime(sg.getCurrentGameTime());
        long comp = MachineLoan.LOAN.equals(l.getKind()) ? compensation(l) : 0;
        if (comp > 0) {
            l.setCompensation(comp);
            outbox.money(sg, -comp, MoneyReason.COMPENSATION, "Schaden an " + l.getVehicleName(),
                    new Related(RELATED, l.getId()));
            if (l.getStartCondition() - l.getLastCondition() >= cfg().getDamageTrustPoints()) {
                trust.recordEvent(l.getCharacter(), cfg().getDamageTrustDelta(), TrustReason.MACHINE_LOAN_DAMAGE,
                        l.getVehicleName());
            }
        }
        narration.request(sg, NarrationEventType.MACHINE_LOAN_RETURNED).from(l.getCharacter())
                .facts(facts(l).put("compensation", comp).put("lateDays", l.getLateDays())
                        .put("reason", l.getEndReason() == null ? "END" : l.getEndReason()).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, l.getId()).submit();
        diary.addAuto(sg, "FARM_WORK", l.getVehicleName() + " zurückgegeben", "An " + l.getCharacter().getName()
                + (l.getLateDays() > 0 ? ", " + l.getLateDays() + " Tag(e) zu spät" : "")
                + (comp > 0 ? ", Schadenersatz " + comp + " €." : "."), RELATED, l.getId());
    }

    /**
     * FailedInstructionService: the game refused an instruction of the loan. Delivery: NO_SPACE = a new attempt the
     * next game day (like R3-V2), otherwise the loan fails. Return: in use or coupled = reminder, new attempt and rent +
     * surcharge the next day. Rent: the neighbour takes the machine back at once. Demo purchase: the machine goes back.
     */
    @Transactional
    public boolean onInstructionFailed(Long loanId, InstructionType type, String message) {
        MachineLoan l = loans.findById(loanId).orElse(null);
        if (l == null || !l.running()) {
            return false;
        }
        Savegame sg = l.getSavegame();
        long now = sg.getCurrentGameTime();
        String reason = VehicleTradeService.reasonOf(message);
        if (type == InstructionType.VEHICLE_SPAWN && MachineLoan.DELIVERING.equals(l.getStatus())) {
            if ("NO_SPACE".equals(reason) && l.getAttempts() < used().getSpawnMaxAttempts()) {
                l.setNextAttemptGameTime(now + GameTime.days(1));
                hint(sg, l, "FarmPulse: Kein freier Shop-Platz für " + l.getVehicleName() + " – bitte Platz freiräumen.");
                return true;
            }
            l.setStatus(MachineLoan.FAILED);
            l.setEndReason(reason);
            l.setClosedGameTime(now);
            narration.request(sg, NarrationEventType.MACHINE_LOAN_FAILED).from(l.getCharacter())
                    .facts(facts(l).put("reason", reason).build())
                    .category(CommunicationCategory.CONTRACT).related(RELATED, l.getId()).submit();
            return true;
        }
        if (type == InstructionType.VEHICLE_REMOVE && MachineLoan.RETURNING.equals(l.getStatus())) {
            if ("VEHICLE_NOT_FOUND".equals(reason) || "NOT_OWN_VEHICLE".equals(reason)) {
                lost(sg, l);
                return true;
            }
            l.setStatus(MachineLoan.ACTIVE);
            if (MachineLoan.LOAN.equals(l.getKind()) && !"RENT_MISSED".equals(l.getEndReason())) {
                l.setEndReason("LATE:" + reason);
            }
            hint(sg, l, "VEHICLE_ATTACHED".equals(reason)
                    ? "FarmPulse: Bitte " + l.getVehicleName() + " abkoppeln – " + l.getCharacter().getName() + " holt sie morgen ab."
                    : "FarmPulse: Bitte " + l.getVehicleName() + " abstellen – " + l.getCharacter().getName() + " holt sie morgen ab.");
            return true;
        }
        if (type == InstructionType.MONEY_TRANSACTION && MachineLoan.ACTIVE.equals(l.getStatus())
                && MachineLoan.LOAN.equals(l.getKind())) {
            // a daily rent was refused (no money): the neighbour takes the machine back at once
            trust.recordEvent(l.getCharacter(), cfg().getRentMissedTrustDelta(), TrustReason.MACHINE_LOAN_RENT_MISSED,
                    l.getVehicleName());
            narration.request(sg, NarrationEventType.MACHINE_LOAN_RECALLED).from(l.getCharacter())
                    .facts(facts(l).build()).category(CommunicationCategory.CONTRACT).related(RELATED, l.getId()).submit();
            giveBack(sg, l, "RENT_MISSED");
            return true;
        }
        if (type == InstructionType.MONEY_TRANSACTION && MachineLoan.PURCHASE_OFFER.equals(l.getStatus())) {
            VehicleDeal d = l.getVehicleDealId() == null ? null : deals.findById(l.getVehicleDealId()).orElse(null);
            if (d != null) {
                d.setStatus(VehicleDeal.FAILED);
                d.setFailureReason(reason);
                d.setClosedGameTime(now);
            }
            giveBack(sg, l, "NOT_BOUGHT");
            return true;
        }
        return false;
    }

    private void hint(Savegame sg, MachineLoan l, String text) {
        outbox.notification(sg, text, "WARNING", sg.getCurrentGameTime() + GameTime.days(used().getNotificationDays()),
                new Related(RELATED, l.getId()));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private NarrationFacts.Builder facts(MachineLoan l) {
        return NarrationFacts.builder().put("vehicleName", l.getVehicleName()).put("kind", l.getKind())
                .put("days", l.getDays()).put("dailyRent", l.getDailyRent());
    }

    private Character neighbor(Savegame sg, Long id) {
        return characters.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .filter(NeighborService::isNeighbor).orElseThrow(() -> new NotFoundException("neighbour " + id));
    }

    private double lifetime(Savegame sg, String xml) {
        return catalog(sg).stream().filter(v -> v.xmlFilename().equals(xml)).findFirst()
                .map(v -> v.lifetime() == null ? 0 : v.lifetime()).orElse(0.0);
    }

    private Boolean motorized(Savegame sg, String xml) {
        return catalog(sg).stream().filter(v -> v.xmlFilename().equals(xml)).findFirst()
                .map(BridgeDtos.StoreVehicle::motorized).orElse(null);
    }

    public List<MachineLoan> list(Savegame sg) {
        return loans.findBySavegameOrderByIdDesc(sg);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
