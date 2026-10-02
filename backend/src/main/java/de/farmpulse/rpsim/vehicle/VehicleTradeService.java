package de.farmpulse.rpsim.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Initiator;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationDirection;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.VehicleDeal;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.VehicleDealRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-V2 / R3-V3: used machines (owner decisions in QUESTIONS.md).
 * <ul>
 *   <li>Purchase: at a month start the workshop (price + markup) or an active neighbour (price − discount) offers a
 *       machine of the shop catalog (R3-V1) with rolled used values; the price follows the game's formula
 *       ({@link #usedPrice}). The player negotiates with the negotiation engine ({@code AssetType.VEHICLE}, DIRECT);
 *       after the agreement {@code VEHICLE_SPAWN} brings the machine to a shop place, the mod books the price itself.
 *       {@code NO_SPACE}: a new attempt every game day up to spawn-max-attempts, then the deal fails.</li>
 *   <li>Sale: the player offers an own machine with an asking price; 1–3 active neighbours answer (SALE_OFFER), at most
 *       sale-cap × the game value. After the agreement VEHICLE_REMOVE + VEHICLE_SALE as one batch.</li>
 * </ul>
 */
@Service
public class VehicleTradeService {

    public static final String RELATED = "VEHICLE_DEAL";
    static final List<String> RUNNING = List.of(VehicleDeal.OPEN, VehicleDeal.AGREED);

    private final VehicleDealRepository deals;
    private final NegotiationRepository negotiations;
    private final NegotiationEngine engine;
    private final OutboxInstructionRepository instructions;
    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final FactsService facts;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;

    /** Roadmap V3.1 R31-A2: borrowed and demo machines cannot be sold. */
    private final de.farmpulse.rpsim.farmwork.LoanedVehicles loaned;

    public VehicleTradeService(VehicleDealRepository deals, NegotiationRepository negotiations, NegotiationEngine engine,
                               OutboxInstructionRepository instructions, SavegameRepository savegames,
                               CharacterRepository characters, FactsService facts, OutboxService outbox,
                               ServiceRoleService roles, CharacterLookup lookup, NarrationRequestService narration,
                               DiaryService diary, RandomSource random, RpsimProperties props, de.farmpulse.rpsim.farmwork.LoanedVehicles loaned) {
        this.loaned = loaned;
        this.deals = deals;
        this.negotiations = negotiations;
        this.engine = engine;
        this.instructions = instructions;
        this.savegames = savegames;
        this.characters = characters;
        this.facts = facts;
        this.outbox = outbox;
        this.roles = roles;
        this.lookup = lookup;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.UsedVehicle cfg() {
        return props.getFormulas().getUsedVehicle();
    }

    // ------------------------------------------------------------------------------------------ formula

    /**
     * Used price of the game (Vehicle.calculateSellPrice without repair costs, ROADMAP_V3 R3-V2):
     * hour factor = 1 − hours ^ exponent / lifetime (exponent 1.3 without an engine, else 1.0),
     * age factor = min(−0.1 · ln(years) + 0.75; 0.85), price = max(list × hour factor × age factor; 3 % of list).
     * {@code motorized} null (not exported) counts as motorised.
     */
    public static long usedPrice(long listPrice, double lifetime, Boolean motorized, int ageMonths, int operatingHours,
                                 RpsimProperties.UsedVehicle cfg) {
        double exponent = Boolean.FALSE.equals(motorized) ? cfg.getUnmotorizedExponent() : cfg.getMotorizedExponent();
        double hourFactor = lifetime > 0 ? 1 - Math.pow(operatingHours, exponent) / lifetime : 1;
        double years = ageMonths / (double) GameTime.PERIODS_PER_YEAR;
        double ageFactor = years <= 0 ? 0.85 : Math.min(-0.1 * Math.log(years) + 0.75, 0.85);
        return Math.round(Math.max(listPrice * hourFactor * ageFactor, listPrice * cfg.getMinPriceShare()));
    }

    /** Operating hours for a target hour factor: (lifetime × (1 − factor)) ^ (1 / exponent). */
    public static int hoursFor(double hourFactor, double lifetime, Boolean motorized, RpsimProperties.UsedVehicle cfg) {
        double exponent = Boolean.FALSE.equals(motorized) ? cfg.getUnmotorizedExponent() : cfg.getMotorizedExponent();
        return (int) Math.floor(Math.pow(Math.max(0, lifetime * (1 - hourFactor)), 1 / exponent));
    }

    // ------------------------------------------------------------------------------------------ purchase offers

    @EventListener
    @Order(80)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        if (!cfg().isEnabled()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!runningBuy(sg).isEmpty() || !random.chance(cfg().getOfferProbability())) {
            return;
        }
        offer(sg);
    }

    List<VehicleDeal> runningBuy(Savegame sg) {
        return deals.findBySavegameAndStatusInOrderByIdAsc(sg, RUNNING).stream()
                .filter(d -> VehicleDeal.BUY.equals(d.getDirection())).toList();
    }

    /** Catalog entries that can be offered: list price in the range, lifetime known. */
    List<BridgeDtos.StoreVehicle> candidates(Savegame sg) {
        return facts.marketContext(sg).map(BridgeDtos.MarketContext::storeVehicles).orElse(List.of()).stream()
                .filter(v -> v != null && v.xmlFilename() != null && !v.xmlFilename().isBlank() && v.price() != null
                        && v.price() >= cfg().getMinListPrice() && v.price() <= cfg().getMaxListPrice()
                        && v.lifetime() != null && v.lifetime() > 0)
                .toList();
    }

    /** One offer of the workshop or an active neighbour (empty without a catalog). */
    @Transactional
    public Optional<VehicleDeal> offer(Savegame sg) {
        List<BridgeDtos.StoreVehicle> list = candidates(sg);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        BridgeDtos.StoreVehicle item = random.pick(list);
        int age = random.intBetween(cfg().getAgeMonthsMin(), cfg().getAgeMonthsMax());
        int hours = hoursFor(random.uniform(cfg().getHourFactorMin(), cfg().getHourFactorMax()), item.lifetime(),
                item.motorized(), cfg());
        double damage = round2(random.uniform(cfg().getDamageMin(), cfg().getDamageMax()));
        double wear = round2(random.uniform(cfg().getWearMin(), cfg().getWearMax()));
        long listPrice = Math.round(item.price());
        long gamePrice = usedPrice(listPrice, item.lifetime(), item.motorized(), age, hours, cfg());
        List<Character> neighbors = activeNeighbors(sg);
        boolean workshop = neighbors.isEmpty() || random.chance(cfg().getWorkshopShare());
        Character seller = workshop ? roles.ensure(sg, CharacterRole.WORKSHOP) : random.pick(neighbors);
        long base = Math.round(gamePrice * (workshop ? 1 + cfg().getWorkshopMarkup() : 1 - cfg().getNeighborDiscount()));
        long now = sg.getCurrentGameTime();
        VehicleDeal d = new VehicleDeal();
        d.setSavegame(sg);
        d.setDirection(VehicleDeal.BUY);
        d.setStatus(VehicleDeal.OPEN);
        d.setSellerKind(workshop ? VehicleDeal.WORKSHOP : VehicleDeal.NEIGHBOR);
        d.setCharacter(seller);
        d.setStoreXmlFilename(item.xmlFilename());
        d.setVehicleName(item.name() == null ? item.xmlFilename() : item.name());
        d.setCategoryName(item.categoryName());
        d.setListPrice(listPrice);
        d.setAgeMonths(age);
        d.setOperatingHours(hours);
        d.setDamage(damage);
        d.setWear(wear);
        d.setGamePrice(gamePrice);
        d.setBasePrice(base);
        d.setCreatedGameTime(now);
        deals.save(d);
        Negotiation n = engine.openVehicle(sg, NegotiationKind.DIRECT, NegotiationDirection.PLAYER_BUYS, Initiator.CHARACTER,
                d.getId(), base, seller, now + GameTime.days(cfg().getNegotiationDays()), null, null);
        engine.counterpartOffer(n, base);
        narration.request(sg, NarrationEventType.VEHICLE_OFFER).from(seller)
                .facts(NarrationFacts.builder().put("vehicleName", d.getVehicleName()).put("sellerKind", d.getSellerKind())
                        .put("ageYears", Math.round(age / 1.2) / 10.0).put("operatingHours", hours)
                        .put("damagePercent", Math.round(damage * 100)).put("price", base)
                        .put("validDays", Math.round(cfg().getNegotiationDays())).build())
                .category(CommunicationCategory.NEGOTIATION).related(NegotiationEngine.RELATED, n.getId())
                .formLink("/werkstatt?negotiation=" + n.getId()).submit();
        return Optional.of(d);
    }

    private List<Character> activeNeighbors(Savegame sg) {
        return characters.findBySavegameAndRoleAndStatus(sg, CharacterRole.NEIGHBOR_FARMER, CharacterStatus.ACTIVE);
    }

    // ------------------------------------------------------------------------------------------ sale offers

    /** "Zum Verkauf anbieten": 1–3 active neighbours answer with a first offer of 100–110 % of the game value. */
    @Transactional
    public VehicleDeal offerForSale(Savegame sg, String vehicleId, long askingPrice) {
        if (askingPrice <= 0) {
            throw new BusinessRuleException("INVALID_PRICE", "Der Wunschpreis muss positiv sein.");
        }
        BridgeDtos.Vehicle vehicle = ownVehicles(sg).stream().filter(v -> v.uniqueId().equals(vehicleId)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("VEHICLE_NOT_FOUND", "Diese Maschine gehört nicht (mehr) zum Hof."));
        if (vehicle.value() == null || vehicle.value() <= 0) {
            throw new BusinessRuleException("NO_VALUE", "Für diese Maschine nennt das Spiel keinen Wert.");
        }
        if (onSale(sg, vehicleId).isPresent()) {
            throw new BusinessRuleException("VEHICLE_ON_SALE", "Diese Maschine wird bereits angeboten.");
        }
        List<Character> neighbors = new ArrayList<>(activeNeighbors(sg));
        if (neighbors.isEmpty()) {
            throw new BusinessRuleException("NO_BUYERS", "Im Dorf gibt es gerade keinen Nachbarn, der kaufen könnte.");
        }
        Collections.shuffle(neighbors, new java.util.Random(random.nextLong()));
        int count = Math.min(neighbors.size(), random.intBetween(cfg().getSaleBuyersMin(), cfg().getSaleBuyersMax()));
        long value = Math.round(vehicle.value());
        long now = sg.getCurrentGameTime();
        VehicleDeal d = new VehicleDeal();
        d.setSavegame(sg);
        d.setDirection(VehicleDeal.SELL);
        d.setStatus(VehicleDeal.OPEN);
        d.setVehicleId(vehicleId);
        d.setVehicleName(name(vehicle));
        d.setStoreXmlFilename(vehicle.xmlFilename());
        d.setGamePrice(value);
        d.setBasePrice(value);
        d.setAskingPrice(askingPrice);
        d.setSaleGroupId("vsale_" + UUID.randomUUID().toString().substring(0, 8));
        d.setCreatedGameTime(now);
        deals.save(d);
        for (Character buyer : neighbors.subList(0, count)) {
            Negotiation n = engine.openVehicle(sg, NegotiationKind.SALE_OFFER, NegotiationDirection.PLAYER_SELLS,
                    Initiator.CHARACTER, d.getId(), value, buyer, now + GameTime.days(cfg().getNegotiationDays()),
                    askingPrice, d.getSaleGroupId());
            long first = Math.min(Math.round(value * random.uniform(cfg().getSaleOfferMin(), cfg().getSaleOfferMax())),
                    engine.vehicleMaxAccept(n));
            engine.counterpartOffer(n, first);
            narration.request(sg, NarrationEventType.VEHICLE_SALE_OFFER).from(buyer)
                    .facts(NarrationFacts.builder().put("vehicleName", d.getVehicleName()).put("askingPrice", askingPrice)
                            .put("offer", first).put("validDays", Math.round(cfg().getNegotiationDays())).build())
                    .category(CommunicationCategory.NEGOTIATION).related(NegotiationEngine.RELATED, n.getId())
                    .formLink("/werkstatt?negotiation=" + n.getId()).submit();
        }
        diary.addAuto(sg, "NEGOTIATION", d.getVehicleName() + " zum Verkauf angeboten", "Wunschpreis " + askingPrice
                + " €, " + count + (count == 1 ? " Nachbar meldet sich." : " Nachbarn melden sich."), RELATED, d.getId());
        return d;
    }

    /**
     * Own (bought) vehicles of the latest export - leased ones are not in assets.vehicles, borrowed and demo machines
     * (Roadmap V3.1 R31-A2) are left out.
     */
    public List<BridgeDtos.Vehicle> ownVehicles(Savegame sg) {
        return facts.latest(sg).map(f -> f.assets() == null || f.assets().vehicles() == null
                ? List.<BridgeDtos.Vehicle>of() : loaned.own(sg, f.assets().vehicles())).orElse(List.of());
    }

    public Optional<VehicleDeal> onSale(Savegame sg, String vehicleId) {
        return deals.findBySavegameAndStatusInOrderByIdAsc(sg, RUNNING).stream()
                .filter(d -> VehicleDeal.SELL.equals(d.getDirection()) && vehicleId.equals(d.getVehicleId())).findFirst();
    }

    static String name(BridgeDtos.Vehicle v) {
        return v.name() != null && !v.name().isBlank() ? v.name() : v.uniqueId();
    }

    // ------------------------------------------------------------------------------------------ agreement

    /** The negotiation engine reached an agreement: deliver (BUY) or remove and book the proceeds (SELL). */
    @EventListener
    public void onAgreed(NegotiationEngine.VehicleAgreed e) {
        Negotiation n = negotiations.findById(e.negotiationId()).orElseThrow();
        VehicleDeal d = deals.findById(Long.parseLong(n.getAssetId())).orElseThrow();
        Savegame sg = n.getSavegame();
        d.setStatus(VehicleDeal.AGREED);
        d.setFinalPrice(n.getFinalPrice());
        d.setCharacter(n.getCounterpartCharacter());
        boolean buy = VehicleDeal.BUY.equals(d.getDirection());
        if (buy && d.getDemoLoanId() != null) {
            // Roadmap V3.1 R31-A2: the demo machine stays on the farm - only the price is booked (MachineLoanService)
            outbox.money(sg, -d.getFinalPrice(), MoneyReason.VEHICLE_PURCHASE, "Kauf " + d.getVehicleName()
                    + " (Vorführmaschine)", new Related(de.farmpulse.rpsim.farmwork.MachineLoanService.RELATED,
                    d.getDemoLoanId()));
        } else if (buy) {
            spawn(sg, d);
        } else {
            outbox.vehicleSale(sg, d.getVehicleId(), d.getFinalPrice(), "Verkauf " + d.getVehicleName(),
                    new Related(RELATED, d.getId()));
        }
        narration.request(sg, NarrationEventType.VEHICLE_DEAL_AGREED).from(d.getCharacter())
                .facts(NarrationFacts.builder().put("vehicleName", d.getVehicleName()).put("finalPrice", d.getFinalPrice())
                        .put("direction", d.getDirection()).build())
                .category(CommunicationCategory.NEGOTIATION).related(NegotiationEngine.RELATED, n.getId()).submit();
    }

    private void spawn(Savegame sg, VehicleDeal d) {
        d.setAttempts(d.getAttempts() + 1);
        d.setNextAttemptGameTime(null);
        outbox.vehicleSpawn(sg, d.getStoreXmlFilename(), d.getAgeMonths(), d.getOperatingHours(), d.getDamage(),
                d.getWear(), d.getFinalPrice(), new Related(RELATED, d.getId()));
    }

    // ------------------------------------------------------------------------------------------ acks

    /** The game delivered (VEHICLE_SPAWN, result.vehicleId) or removed (VEHICLE_REMOVE) the machine. */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        InstructionType type = instructions.findByInstructionId(e.instructionId()).map(o -> o.getType()).orElse(null);
        VehicleDeal d = deals.findById(e.relatedId()).orElse(null);
        if (d == null || !VehicleDeal.AGREED.equals(d.getStatus())
                || (type != InstructionType.VEHICLE_SPAWN && type != InstructionType.VEHICLE_REMOVE)) {
            return;
        }
        Savegame sg = d.getSavegame();
        d.setStatus(VehicleDeal.DONE);
        d.setClosedGameTime(sg.getCurrentGameTime());
        if (type == InstructionType.VEHICLE_SPAWN) {
            Object id = e.result() == null ? null : e.result().get("vehicleId");
            d.setVehicleId(id == null ? null : id.toString());
            diary.addAuto(sg, "NEGOTIATION", d.getVehicleName() + " gekauft", "Gebraucht von "
                    + (d.getCharacter() == null ? "der Werkstatt" : d.getCharacter().getName()) + " für "
                    + d.getFinalPrice() + " € – " + d.getOperatingHours() + " Betriebsstunden, steht auf dem Shop-Platz.",
                    RELATED, d.getId());
            return;
        }
        diary.addAuto(sg, "NEGOTIATION", d.getVehicleName() + " verkauft", "An "
                + (d.getCharacter() == null ? "einen Nachbarn" : d.getCharacter().getName()) + " für "
                + d.getFinalPrice() + " €.", RELATED, d.getId());
        gossip(sg, d);
    }

    /** One message of a random active villager after a sale ("Der Nachbar fährt jetzt deinen alten …"). */
    void gossip(Savegame sg, VehicleDeal d) {
        List<Character> dyn = lookup.activeDynamic(sg).stream()
                .filter(c -> d.getCharacter() == null || !c.getId().equals(d.getCharacter().getId())).toList();
        Optional<Character> teller = dyn.isEmpty() ? lookup.firstActive(sg, CharacterRole.VILLAGER)
                : Optional.of(random.pick(dyn));
        teller.ifPresent(t -> narration.request(sg, NarrationEventType.VEHICLE_SOLD_GOSSIP).from(t)
                .facts(NarrationFacts.builder().put("vehicleName", d.getVehicleName())
                        .put("buyerName", d.getCharacter() == null ? null : d.getCharacter().getName()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit());
    }

    /**
     * FailedInstructionService: the game refused VEHICLE_SPAWN / VEHICLE_REMOVE. NO_SPACE: hint in the game, mail and a
     * new attempt the next game day (at most spawn-max-attempts); anything else ends the deal - nothing was booked.
     */
    @Transactional
    public boolean onInstructionFailed(Long dealId, InstructionType type, String message) {
        VehicleDeal d = deals.findById(dealId).orElse(null);
        if (d == null || !VehicleDeal.AGREED.equals(d.getStatus())
                || (type != InstructionType.VEHICLE_SPAWN && type != InstructionType.VEHICLE_REMOVE)) {
            return false;
        }
        Savegame sg = d.getSavegame();
        long now = sg.getCurrentGameTime();
        String reason = reasonOf(message);
        if (type == InstructionType.VEHICLE_SPAWN && "NO_SPACE".equals(reason) && d.getAttempts() < cfg().getSpawnMaxAttempts()) {
            d.setNextAttemptGameTime(now + GameTime.days(1));
            hint(sg, d, "FarmPulse: Kein freier Shop-Platz für " + d.getVehicleName() + " – bitte Platz freiräumen.");
            narration.request(sg, NarrationEventType.VEHICLE_NO_SPACE).from(d.getCharacter())
                    .facts(NarrationFacts.builder().put("vehicleName", d.getVehicleName()).put("attempt", d.getAttempts())
                            .put("maxAttempts", cfg().getSpawnMaxAttempts()).build())
                    .category(CommunicationCategory.NEGOTIATION).related(RELATED, d.getId()).submit();
            return true;
        }
        d.setStatus(VehicleDeal.FAILED);
        d.setFailureReason(reason);
        d.setClosedGameTime(now);
        if ("VEHICLE_ATTACHED".equals(reason)) {
            hint(sg, d, "FarmPulse: Bitte erst abkoppeln – " + d.getVehicleName() + " wurde nicht verkauft.");
        } else if ("VEHICLE_IN_USE".equals(reason)) {
            hint(sg, d, "FarmPulse: " + d.getVehicleName() + " wird gerade benutzt und wurde nicht verkauft.");
        }
        narration.request(sg, NarrationEventType.VEHICLE_DEAL_FAILED).from(d.getCharacter())
                .facts(NarrationFacts.builder().put("vehicleName", d.getVehicleName()).put("direction", d.getDirection())
                        .put("reason", reason).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, d.getId()).submit();
        diary.addAuto(sg, "NEGOTIATION", "Geschäft über " + d.getVehicleName() + " geplatzt",
                "Das Spiel konnte den " + (VehicleDeal.BUY.equals(d.getDirection()) ? "Kauf" : "Verkauf")
                        + " nicht ausführen (" + reason + "). Es wurde nichts gebucht.", RELATED, d.getId());
        return true;
    }

    /** The mod's reason code (e.g. "NO_SPACE", "unknown type VEHICLE_SPAWN" of an older mod, "BATCH_ABORTED: …"). */
    public static String reasonOf(String message) {
        if (message == null || message.isBlank()) {
            return "UNKNOWN";
        }
        String m = message.trim();
        if (m.startsWith("unknown type")) {
            return "MOD_OUTDATED";
        }
        int colon = m.indexOf(':');
        String code = colon > 0 ? m.substring(0, colon) : m;
        return code.length() > 64 ? code.substring(0, 64) : code;
    }

    private void hint(Savegame sg, VehicleDeal d, String text) {
        outbox.notification(sg, text, "WARNING", sg.getCurrentGameTime() + GameTime.days(cfg().getNotificationDays()),
                new Related(RELATED, d.getId()));
    }

    // ------------------------------------------------------------------------------------------ daily

    /** Daily: a new delivery after NO_SPACE; deals whose negotiations ended without an agreement are closed. */
    @EventListener
    @Order(81)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (VehicleDeal d : deals.findBySavegameAndStatusInOrderByIdAsc(sg, RUNNING)) {
            if (VehicleDeal.AGREED.equals(d.getStatus()) && d.getNextAttemptGameTime() != null
                    && d.getNextAttemptGameTime() <= now) {
                spawn(sg, d);
            } else if (VehicleDeal.OPEN.equals(d.getStatus())) {
                closeIfEnded(sg, d);
            }
        }
    }

    /** An OPEN deal whose negotiations are all closed without an agreement ends. */
    @Transactional
    public void closeIfEnded(Savegame sg, VehicleDeal d) {
        List<Negotiation> list = negotiationsOf(sg, d);
        if (!list.isEmpty() && list.stream().noneMatch(n -> n.getStatus() == NegotiationStatus.OPEN
                || n.getStatus() == NegotiationStatus.ACCEPTED)) {
            d.setStatus(VehicleDeal.ENDED);
            d.setClosedGameTime(sg.getCurrentGameTime());
        }
    }

    public List<Negotiation> negotiationsOf(Savegame sg, VehicleDeal d) {
        return negotiations.findBySavegameAndAssetTypeAndAssetIdOrderByIdAsc(sg, AssetType.VEHICLE, String.valueOf(d.getId()));
    }

    public List<VehicleDeal> list(Savegame sg) {
        return deals.findBySavegameOrderByIdDesc(sg);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
