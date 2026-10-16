package de.farmpulse.rpsim.dealer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.DealerCartItem;
import de.farmpulse.rpsim.domain.DealerDeliveryPoint;
import de.farmpulse.rpsim.domain.DealerDelivery;
import de.farmpulse.rpsim.domain.DealerOrder;
import de.farmpulse.rpsim.domain.DealerOrderItem;
import de.farmpulse.rpsim.domain.DealerTradeIn;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.AbsenceScale;
import de.farmpulse.rpsim.farmwork.LoanedVehicles;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.negotiation.NegotiationFormula;
import de.farmpulse.rpsim.repository.DealerCartItemRepository;
import de.farmpulse.rpsim.repository.DealerDeliveryPointRepository;
import de.farmpulse.rpsim.repository.DealerDeliveryRepository;
import de.farmpulse.rpsim.repository.DealerOrderItemRepository;
import de.farmpulse.rpsim.repository.DealerOrderRepository;
import de.farmpulse.rpsim.repository.DealerTradeInRepository;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap vehicle dealer RFH-B / L (owner decisions 2026-10-15/16, QUESTIONS.md): the cart becomes a request with
 * "zusammen" / "einzeln", delivery points, express and trade-ins; the dealer answers at the next game day change with an
 * offer (one net price = machines - trade-ins, valid 7 game days) negotiated with the engine (AssetType.MACHINE_ORDER).
 * On the agreement 30 % deposit (VEHICLE_DEPOSIT); refused = no order. Each delivery gets its date (L1/L2 rolled,
 * express halved, scaled to the month length, never before 9:00 the next game day), may be delayed once (L4), and is
 * sent as VEHICLE_DELIVERY on its date with the rest, the delivery costs, express and - after "Lieferung erneut
 * versuchen" - the retry fee. A failed delivery waits at the dealer: a hint and a new attempt every game day. Cancelling
 * refunds 50 % of the deposit (100 % after a delay).
 */
@Service
public class DealerOrderService {

    public static final String RELATED_ORDER = "DEALER_ORDER";
    public static final String RELATED_DELIVERY = "DEALER_DELIVERY";
    static final List<String> ACTIVE = List.of(DealerOrder.REQUESTED, DealerOrder.OFFERED, DealerOrder.DEPOSIT_PENDING,
            DealerOrder.ORDERED);
    static final String SHOP_PLACE = "Shop-Platz des Spiels";
    private static final TypeReference<LinkedHashMap<String, Object>> OPTIONS = new TypeReference<>() {
    };

    /** Delivery of a request: point (null = shop place) and express. */
    public record DeliveryChoice(Long deliveryPointId, boolean express) {
    }

    /** "Angebot anfragen": {@code perPosition} = cart item id -> choice ("einzeln"), else {@code all} ("zusammen"). */
    public record RequestInput(boolean together, List<String> tradeIns, DeliveryChoice all,
                               Map<Long, DeliveryChoice> perPosition) {
    }

    private final DealerOrderRepository orders;
    private final DealerDeliveryRepository deliveries;
    private final DealerOrderItemRepository items;
    private final DealerTradeInRepository tradeIns;
    private final DealerCartItemRepository cartItems;
    private final DealerCartService cart;
    private final MachineCatalogService catalog;
    private final DeliveryPointService places;
    private final DealerDeliveryPointRepository points;
    private final MachineDealerService dealers;
    private final NegotiationEngine engine;
    private final NegotiationRepository negotiations;
    private final FactsService facts;
    private final OutboxService outbox;
    private final OutboxInstructionRepository instructions;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final TrustScoreService trust;
    private final CharacterLookup lookup;
    private final SavegameRepository savegames;
    private final AbsenceScale absence;
    private final RandomSource random;
    private final RpsimProperties props;
    private final JsonMapper json;
    private final LoanedVehicles loaned;

    public DealerOrderService(DealerOrderRepository orders, DealerDeliveryRepository deliveries,
                              DealerOrderItemRepository items, DealerTradeInRepository tradeIns,
                              DealerCartItemRepository cartItems, DealerCartService cart, MachineCatalogService catalog,
                              DeliveryPointService places, DealerDeliveryPointRepository points,
                              MachineDealerService dealers, NegotiationEngine engine, NegotiationRepository negotiations,
                              FactsService facts, OutboxService outbox, OutboxInstructionRepository instructions,
                              NarrationRequestService narration, DiaryService diary, TrustScoreService trust,
                              CharacterLookup lookup, SavegameRepository savegames, AbsenceScale absence,
                              RandomSource random, RpsimProperties props, JsonMapper json, LoanedVehicles loaned) {
        this.orders = orders;
        this.deliveries = deliveries;
        this.items = items;
        this.tradeIns = tradeIns;
        this.cartItems = cartItems;
        this.cart = cart;
        this.catalog = catalog;
        this.places = places;
        this.points = points;
        this.dealers = dealers;
        this.engine = engine;
        this.negotiations = negotiations;
        this.facts = facts;
        this.outbox = outbox;
        this.instructions = instructions;
        this.narration = narration;
        this.diary = diary;
        this.trust = trust;
        this.lookup = lookup;
        this.savegames = savegames;
        this.absence = absence;
        this.random = random;
        this.props = props;
        this.json = json;
        this.loaned = loaned;
    }

    private RpsimProperties.MachineDealer cfg() {
        return props.getFormulas().getMachineDealer();
    }

    // ------------------------------------------------------------------------------------------ B1 request

    /**
     * Own machines that may be given in trade: owned (assets.vehicles), no borrowed or demo machine (R31-A2, RFH-V1) and
     * not in another running order.
     */
    @Transactional(readOnly = true)
    public List<BridgeDtos.Vehicle> tradeInCandidates(Savegame sg) {
        Set<String> busy = tradeIns.findBySavegameAndStatus(sg, DealerTradeIn.OPEN).stream()
                .filter(t -> orders.findById(t.getOrderId()).map(o -> ACTIVE.contains(o.getStatus())).orElse(false))
                .map(DealerTradeIn::getVehicleId).collect(Collectors.toSet());
        return loaned.own(sg, vehicles(sg)).stream().filter(v -> v != null && !busy.contains(v.uniqueId())).toList();
    }

    private List<BridgeDtos.Vehicle> vehicles(Savegame sg) {
        return facts.latest(sg).map(f -> f.assets() == null || f.assets().vehicles() == null
                ? List.<BridgeDtos.Vehicle>of() : f.assets().vehicles()).orElse(List.of());
    }

    /** "Angebot anfragen": the cart becomes a request (the cart is empty afterwards). */
    @Transactional
    public DealerOrder request(Savegame sg, RequestInput in) {
        List<DealerCartService.Position> positions = cart.positions(sg);
        if (positions.isEmpty()) {
            throw new BusinessRuleException("DEALER_CART_EMPTY", "Der Warenkorb ist leer.");
        }
        if (positions.stream().anyMatch(p -> p.quote() == null)) {
            throw new IllegalArgumentException("a position of the cart cannot be put together any more");
        }
        Map<String, BridgeDtos.Vehicle> candidates = tradeInCandidates(sg).stream()
                .collect(Collectors.toMap(BridgeDtos.Vehicle::uniqueId, v -> v, (a, b) -> a, LinkedHashMap::new));
        List<String> wanted = in.tradeIns() == null ? List.of() : in.tradeIns().stream().distinct().toList();
        for (String id : wanted) {
            if (!candidates.containsKey(id)) {
                throw new IllegalArgumentException("vehicle " + id + " cannot be given in trade");
            }
        }
        long listTotal = positions.stream().mapToLong(p -> p.quote().result().listPrice()).sum();
        long tradeInValue = wanted.stream().mapToLong(id -> value(candidates.get(id))).sum();
        if (tradeInValue >= listTotal) {
            throw new BusinessRuleException("DEALER_TRADE_IN_TOO_HIGH",
                    "Die Inzahlungsmaschinen sind mehr wert als die Bestellung – bitte weniger in Zahlung geben.");
        }
        DealerOrder o = new DealerOrder();
        o.setSavegame(sg);
        o.setStatus(DealerOrder.REQUESTED);
        o.setTogether(in.together());
        o.setRequestedGameTime(sg.getCurrentGameTime());
        o.setListTotal(listTotal);
        o.setTradeInValue(tradeInValue);
        orders.save(o);
        DealerDelivery shared = null;
        int pos = 0;
        for (DealerCartService.Position p : positions) {
            DeliveryChoice choice = in.together() ? in.all()
                    : in.perPosition() == null ? null : in.perPosition().get(p.item().getId());
            if (choice == null) {
                throw new IllegalArgumentException("delivery choice missing");
            }
            DealerDelivery d = in.together() ? shared : null;
            if (d == null) {
                d = newDelivery(sg, o, choice);
                if (in.together()) {
                    shared = d;
                }
            }
            items.save(item(sg, o, d, ++pos, p, choice.express()));
        }
        for (String id : wanted) {
            BridgeDtos.Vehicle v = candidates.get(id);
            DealerTradeIn t = new DealerTradeIn();
            t.setSavegame(sg);
            t.setOrderId(o.getId());
            t.setVehicleId(id);
            t.setName(v.name() == null ? id : v.name());
            t.setGameValue(value(v));
            t.setStatus(DealerTradeIn.OPEN);
            tradeIns.save(t);
        }
        cartItems.deleteAll(cartItems.findBySavegameOrderByIdAsc(sg));
        return o;
    }

    private static long value(BridgeDtos.Vehicle v) {
        return v.value() == null ? 0 : Math.round(v.value());
    }

    private DealerDelivery newDelivery(Savegame sg, DealerOrder o, DeliveryChoice choice) {
        if (choice.deliveryPointId() != null && places.places(sg).points().stream()
                .noneMatch(x -> x.point().getId().equals(choice.deliveryPointId()) && x.valid())) {
            throw new IllegalArgumentException("delivery point " + choice.deliveryPointId() + " is not valid");
        }
        DealerDelivery d = new DealerDelivery();
        d.setSavegame(sg);
        d.setOrderId(o.getId());
        d.setStatus(DealerDelivery.OPEN);
        d.setDeliveryPointId(choice.deliveryPointId());
        d.setExpress(choice.express());
        d.setDeliveryFee(fee(sg, choice.deliveryPointId()));
        return deliveries.save(d);
    }

    private DealerOrderItem item(Savegame sg, DealerOrder o, DealerDelivery d, int position, DealerCartService.Position p,
                                 boolean express) {
        MachineConfigurator.Result r = p.quote().result();
        BridgeDtos.CatalogItem catalogItem = catalog.requireItem(sg, p.item().getXmlFilename());
        Map<String, Object> options = new TreeMap<>();
        r.configurations().forEach((type, index) -> {
            Map<String, Object> opt = new LinkedHashMap<>();
            BridgeDtos.CatalogOption option = MachineConfigurator.option(MachineConfigurator.type(catalogItem, type), index);
            if (option != null && option.saveId() != null) {
                opt.put("saveId", option.saveId());
            }
            opt.put("index", index);
            options.put(type, opt);
        });
        DealerOrderItem i = new DealerOrderItem();
        i.setSavegame(sg);
        i.setOrderId(o.getId());
        i.setDeliveryId(d.getId());
        i.setPosition(position);
        i.setXmlFilename(p.item().getXmlFilename());
        i.setName(p.item().getName());
        i.setChoiceJson(p.item().getConfigurationsJson());
        i.setOptionsJson(json.writeValueAsString(options));
        i.setListPrice(r.listPrice());
        i.setExpressFee(express ? Math.round(r.listPrice() * cfg().getExpressShare()) : 0);
        i.setDaysMin(r.daysMin());
        i.setDaysMax(r.daysMax());
        i.setStatus(DealerOrderItem.OPEN);
        return i;
    }

    // ------------------------------------------------------------------------------------------ L6 delivery costs

    /** L6: base fee + per km from the nearest shop place; the shop place (null) costs the base fee. */
    long fee(Savegame sg, Long pointId) {
        DealerDeliveryPoint p = pointId == null ? null : points.findById(pointId).orElse(null);
        if (p == null) {
            return cfg().getDeliveryBaseFee();
        }
        List<BridgeDtos.StorePlace> shop = facts.marketContext(sg).map(BridgeDtos.MarketContext::storePlaces)
                .orElse(null);
        if (shop == null || shop.isEmpty()) {
            return cfg().getDeliveryBaseFee();
        }
        double km = shop.stream().filter(s -> s.x() != null && s.z() != null)
                .mapToDouble(s -> Math.hypot(s.x() - p.getX(), s.z() - p.getZ())).min().orElse(0) / 1000.0;
        return cfg().getDeliveryBaseFee() + Math.round(km * cfg().getDeliveryFeePerKm());
    }

    /** O2: another delivery point until the delivery is sent; the delivery costs follow it. */
    @Transactional
    public void changePlace(Savegame sg, long deliveryId, Long pointId) {
        DealerDelivery d = ownDelivery(sg, deliveryId);
        if (!List.of(DealerDelivery.OPEN, DealerDelivery.PLANNED, DealerDelivery.WAITING).contains(d.getStatus())) {
            throw new BusinessRuleException("DEALER_DELIVERY_SENT", "Die Lieferung ist schon unterwegs.");
        }
        if (pointId != null && places.places(sg).points().stream()
                .noneMatch(x -> x.point().getId().equals(pointId) && x.valid())) {
            throw new IllegalArgumentException("delivery point " + pointId + " is not valid");
        }
        d.setDeliveryPointId(pointId);
        d.setDeliveryFee(fee(sg, pointId));
    }

    // ------------------------------------------------------------------------------------------ B1 / B2 offer

    @EventListener
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long dayStart = GameTime.dayIndex(e.gameTime()) * GameTime.MS_PER_DAY;
        for (DealerOrder o : orders.findBySavegameAndStatusIn(sg, ACTIVE)) {
            if (DealerOrder.REQUESTED.equals(o.getStatus()) && o.getRequestedGameTime() < dayStart) {
                offer(sg, o);
            }
        }
        sync(sg);
        // L5: a delivery waiting at the dealer - a hint in the game and a new attempt every game day
        for (DealerDelivery d : deliveries.findBySavegameAndStatusIn(sg, List.of(DealerDelivery.WAITING))) {
            hint(sg, d, "FarmPulse: " + names(itemsOf(d)) + " wartet beim Händler – " + reasonText(sg, d));
            send(sg, d, false);
        }
    }

    /** The dealer's offer (next game day change after the request). */
    @Transactional
    public void offer(Savegame sg, DealerOrder o) {
        Character dealer = dealers.open(sg);
        double score = trust.getCurrentTrust(dealer);
        RpsimProperties.Trust tcfg = props.getFormulas().getTrust();
        double norm = (score - tcfg.getMin()) / Math.max(1, tcfg.getMax() - tcfg.getMin());
        double share = cfg().getTradeInMin() + (cfg().getTradeInMax() - cfg().getTradeInMin())
                * Math.max(0, Math.min(1, norm));
        long tradeInOffer = 0;
        for (DealerTradeIn t : tradeIns.findByOrderIdOrderByIdAsc(o.getId())) {
            t.setOfferValue(Math.round(t.getGameValue() * share));
            tradeInOffer += t.getOfferValue();
        }
        long list = o.getListTotal();
        long formula = NegotiationFormula.effectiveMinAccept(list, dealer.getNegotiationTrait(), score,
                props.getFormulas().getNegotiation());
        long listFloor = Math.max(Math.round(list * (1 - cfg().getMaxDiscount())), formula);
        long now = sg.getCurrentGameTime();
        o.setTradeInOffer(tradeInOffer);
        o.setOfferPrice(list - tradeInOffer);
        o.setFloorPrice(Math.min(list - tradeInOffer, listFloor - o.getTradeInValue()));
        o.setOfferGameTime(now);
        o.setValidUntilGameTime(now + GameTime.days(cfg().getOfferValidDays()));
        Negotiation n = engine.openMachineOrder(sg, o.getId(), o.getOfferPrice(), o.getFloorPrice(), dealer,
                o.getValidUntilGameTime());
        o.setNegotiationId(n.getId());
        o.setStatus(DealerOrder.OFFERED);
        List<DealerOrderItem> all = items.findByOrderIdOrderByPositionAsc(o.getId());
        narration.request(sg, NarrationEventType.DEALER_OFFER).from(dealer)
                .facts(NarrationFacts.builder().put("machines", names(all)).put("listTotal", list)
                        .put("tradeIn", tradeInOffer).put("price", o.getOfferPrice())
                        .put("depositPercent", Math.round(cfg().getDepositShare() * 100))
                        .put("deposit", Math.round(o.getOfferPrice() * cfg().getDepositShare()))
                        .put("span", span(sg, all)).put("validUntil", GameTimeText.of(o.getValidUntilGameTime())).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED_ORDER, o.getId())
                .formLink("/haendler/bestellungen").submit();
    }

    /** Offer span of the delivery times ("2–4 Spieltage") for the mail. */
    private String span(Savegame sg, List<DealerOrderItem> all) {
        double min = all.stream().mapToDouble(DealerOrderItem::getDaysMin).min().orElse(0);
        double max = all.stream().mapToDouble(DealerOrderItem::getDaysMax).max().orElse(0);
        double factor = AbsenceScale.factor(absence.daysPerPeriod(sg), props.getFormulas().getAbsenceScaling());
        return GameTimeText.days(min * factor) + "–" + GameTimeText.days(max * factor) + " Spieltage";
    }

    /** The end of a negotiation without agreement (refused, expired, declined) ends the order. */
    @Transactional
    public void sync(Savegame sg) {
        for (DealerOrder o : orders.findBySavegameAndStatusIn(sg, List.of(DealerOrder.OFFERED))) {
            Negotiation n = o.getNegotiationId() == null ? null : negotiations.findById(o.getNegotiationId()).orElse(null);
            if (n == null || n.getStatus() == NegotiationStatus.OPEN || n.getStatus() == NegotiationStatus.ACCEPTED) {
                continue;
            }
            o.setStatus(n.getStatus() == NegotiationStatus.EXPIRED ? DealerOrder.EXPIRED : DealerOrder.REJECTED);
            o.setClosedGameTime(sg.getCurrentGameTime());
            closeDeliveries(o);
        }
    }

    private void closeDeliveries(DealerOrder o) {
        deliveries.findByOrderIdOrderByIdAsc(o.getId()).forEach(d -> d.setStatus(DealerDelivery.CANCELLED));
        items.findByOrderIdOrderByPositionAsc(o.getId()).forEach(i -> i.setStatus(DealerOrderItem.CANCELLED));
    }

    /** "Zurück in den Warenkorb" after "Keine Einigung" or "Abgelaufen" (up to max-positions). */
    @Transactional
    public void backToCart(Savegame sg, long orderId) {
        DealerOrder o = own(sg, orderId);
        if (!DealerOrder.REJECTED.equals(o.getStatus()) && !DealerOrder.EXPIRED.equals(o.getStatus()) || o.isReturnedToCart()) {
            throw new BusinessRuleException("DEALER_ORDER_RUNNING", "Die Bestellung läuft noch.");
        }
        for (DealerOrderItem i : items.findByOrderIdOrderByPositionAsc(o.getId())) {
            cart.add(sg, i.getXmlFilename(), json.readValue(i.getChoiceJson(), new TypeReference<TreeMap<String, Integer>>() {
            }));
        }
        o.setReturnedToCart(true);
    }

    // ------------------------------------------------------------------------------------------ B3 agreement

    @EventListener
    @Transactional
    public void onAgreed(NegotiationEngine.MachineOrderAgreed e) {
        orders.findByNegotiationId(e.negotiationId()).ifPresent(o -> agree(o.getSavegame(), o,
                negotiations.findById(e.negotiationId()).orElseThrow().getFinalPrice()));
    }

    /** Shares, delivery dates, trade-in repair prices and the deposit. */
    @Transactional
    public void agree(Savegame sg, DealerOrder o, long price) {
        long now = sg.getCurrentGameTime();
        o.setAgreedPrice(price);
        long deposit = Math.round(price * cfg().getDepositShare());
        o.setDeposit(deposit);
        List<DealerOrderItem> all = items.findByOrderIdOrderByPositionAsc(o.getId());
        long priceLeft = price;
        long depositLeft = deposit;
        for (int k = 0; k < all.size(); k++) {
            DealerOrderItem i = all.get(k);
            boolean last = k == all.size() - 1;
            long ps = last ? priceLeft : Math.round(price * (double) i.getListPrice() / o.getListTotal());
            long ds = last ? depositLeft : Math.round(deposit * (double) i.getListPrice() / o.getListTotal());
            i.setPriceShare(ps);
            i.setDepositShare(ds);
            priceLeft -= ps;
            depositLeft -= ds;
        }
        int dpp = absence.daysPerPeriod(sg);
        boolean first = true;
        List<DealerTradeIn> trades = tradeIns.findByOrderIdOrderByIdAsc(o.getId());
        for (DealerDelivery d : deliveries.findByOrderIdOrderByIdAsc(o.getId())) {
            double days = itemsOf(d).stream().mapToDouble(i -> random.uniform(i.getDaysMin(), i.getDaysMax()))
                    .max().orElse(0);
            if (d.isExpress()) {
                days *= cfg().getExpressFactor();
            }
            d.setAgreedGameTime(now);
            d.setDueGameTime(DeliveryTime.at(now, days, dpp, props.getFormulas().getAbsenceScaling(),
                    cfg().getEarliestDeliveryHour()));
            d.setCarriesTradeIns(first && !trades.isEmpty());
            d.setStatus(DealerDelivery.PLANNED);
            first = false;
        }
        Map<String, BridgeDtos.Vehicle> now2 = vehicles(sg).stream()
                .collect(Collectors.toMap(BridgeDtos.Vehicle::uniqueId, v -> v, (a, b) -> a));
        for (DealerTradeIn t : trades) {
            BridgeDtos.Vehicle v = now2.get(t.getVehicleId());
            t.setRepairPriceAtAgreement(v == null ? null : v.repairPrice());
        }
        if (deposit > 0) {
            o.setStatus(DealerOrder.DEPOSIT_PENDING);
            outbox.money(sg, -deposit, MoneyReason.VEHICLE_DEPOSIT, "Anzahlung " + names(all),
                    new Related(RELATED_ORDER, o.getId()));
        } else {
            ordered(sg, o);
        }
    }

    private void ordered(Savegame sg, DealerOrder o) {
        o.setStatus(DealerOrder.ORDERED);
        List<DealerOrderItem> all = items.findByOrderIdOrderByPositionAsc(o.getId());
        List<DealerDelivery> ds = deliveries.findByOrderIdOrderByIdAsc(o.getId());
        narration.request(sg, NarrationEventType.DEALER_ORDER_CONFIRMED).from(dealer(sg))
                .facts(NarrationFacts.builder().put("machines", names(all)).put("price", o.getAgreedPrice())
                        .put("deposit", o.getDeposit())
                        .put("dates", ds.stream().map(d -> GameTimeText.of(d.getDueGameTime())).collect(Collectors.joining(", ")))
                        .put("place", ds.stream().map(d -> placeName(d.getDeliveryPointId())).distinct()
                                .collect(Collectors.joining(", "))).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED_ORDER, o.getId())
                .formLink("/haendler/bestellungen").submit();
        diary.addAuto(sg, "NEGOTIATION", "Neumaschine bestellt: " + names(all),
                "Vereinbart: " + o.getAgreedPrice() + " €, Anzahlung " + o.getDeposit() + " €.", RELATED_ORDER, o.getId());
    }

    private Character dealer(Savegame sg) {
        return dealers.dealer(sg).orElse(null);
    }

    // ------------------------------------------------------------------------------------------ L4 / L5 deliveries

    @EventListener
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        tick(sg);
    }

    /** Delays, trade-ins that disappeared and deliveries that are due. */
    @Transactional
    public void tick(Savegame sg) {
        long now = sg.getCurrentGameTime();
        Set<String> present = vehicles(sg).stream().map(BridgeDtos.Vehicle::uniqueId).collect(Collectors.toSet());
        boolean haveFacts = facts.latest(sg).isPresent();
        for (DealerOrder o : orders.findBySavegameAndStatusIn(sg, List.of(DealerOrder.ORDERED))) {
            List<DealerDelivery> ds = deliveries.findByOrderIdOrderByIdAsc(o.getId());
            boolean tradeInsUnderway = ds.stream().anyMatch(d -> d.isCarriesTradeIns()
                    && (DealerDelivery.SENT.equals(d.getStatus()) || DealerDelivery.DELIVERED.equals(d.getStatus())));
            if (haveFacts && !tradeInsUnderway) {
                for (DealerTradeIn t : tradeIns.findByOrderIdOrderByIdAsc(o.getId())) {
                    if (DealerTradeIn.OPEN.equals(t.getStatus()) && !present.contains(t.getVehicleId())) {
                        t.setStatus(DealerTradeIn.LOST);
                        narration.request(sg, NarrationEventType.DEALER_TRADE_IN_LOST).from(dealer(sg))
                                .facts(NarrationFacts.builder().put("name", t.getName()).put("amount", t.getOfferValue())
                                        .build())
                                .category(CommunicationCategory.NEGOTIATION).related(RELATED_ORDER, o.getId()).submit();
                    }
                }
            }
            for (DealerDelivery d : ds) {
                if (!DealerDelivery.PLANNED.equals(d.getStatus()) || d.getDueGameTime() == null) {
                    continue;
                }
                long length = d.getDueGameTime() - d.getAgreedGameTime();
                if (!d.isDelayChecked() && now >= d.getDueGameTime() - Math.round(length * cfg().getDelayCheckRemainingShare())) {
                    d.setDelayChecked(true);
                    double p = cfg().getDelayProbability() * (d.isExpress() ? cfg().getExpressDelayFactor() : 1);
                    if (random.chance(p)) {
                        delay(sg, o, d);
                    }
                }
                if (now >= d.getDueGameTime()) {
                    send(sg, d, false);
                }
            }
        }
    }

    private void delay(Savegame sg, DealerOrder o, DealerDelivery d) {
        double days = random.uniform(cfg().getDelayDaysMin(), cfg().getDelayDaysMax());
        d.setDueGameTime(DeliveryTime.at(d.getDueGameTime(), days, absence.daysPerPeriod(sg),
                props.getFormulas().getAbsenceScaling(), cfg().getEarliestDeliveryHour()));
        d.setDelayed(true);
        narration.request(sg, NarrationEventType.DEALER_DELAY).from(dealer(sg))
                .facts(NarrationFacts.builder().put("machines", names(itemsOf(d)))
                        .put("date", GameTimeText.of(d.getDueGameTime())).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED_ORDER, o.getId())
                .formLink("/haendler/bestellungen").submit();
    }

    public List<DealerOrderItem> itemsOf(DealerDelivery d) {
        return items.findByDeliveryIdOrderByPositionAsc(d.getId()).stream()
                .filter(i -> !DealerOrderItem.CANCELLED.equals(i.getStatus())).toList();
    }

    /** The bookings of a delivery in their order (zero amounts left out). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> bookings(Savegame sg, DealerDelivery d, boolean retry) {
        List<DealerOrderItem> its = itemsOf(d);
        long rest = its.stream().mapToLong(i -> nz(i.getPriceShare()) - nz(i.getDepositShare())).sum();
        if (d.isCarriesTradeIns()) {
            Map<String, BridgeDtos.Vehicle> now = vehicles(sg).stream()
                    .collect(Collectors.toMap(BridgeDtos.Vehicle::uniqueId, v -> v, (a, b) -> a));
            for (DealerTradeIn t : tradeIns.findByOrderIdOrderByIdAsc(d.getOrderId())) {
                if (DealerTradeIn.LOST.equals(t.getStatus())) {
                    rest += t.getOfferValue();
                } else if (DealerTradeIn.OPEN.equals(t.getStatus())) {
                    BridgeDtos.Vehicle v = now.get(t.getVehicleId());
                    if (v != null && v.repairPrice() != null && t.getRepairPriceAtAgreement() != null) {
                        rest += Math.max(0, v.repairPrice() - t.getRepairPriceAtAgreement()); // B4: only damage counts
                    }
                }
            }
        }
        String names = names(its);
        List<Map<String, Object>> out = new ArrayList<>();
        add(out, rest, MoneyReason.VEHICLE_NEW_PURCHASE, names);
        add(out, effectiveFee(sg, d), MoneyReason.VEHICLE_DELIVERY_FEE, "Lieferung");
        add(out, its.stream().mapToLong(DealerOrderItem::getExpressFee).sum(), MoneyReason.VEHICLE_EXPRESS_FEE,
                "Expresslieferung");
        if (retry) {
            add(out, cfg().getRetryFee(), MoneyReason.VEHICLE_DELIVERY_FEE, "Neuer Lieferversuch");
        }
        return out;
    }

    private static void add(List<Map<String, Object>> out, long amount, MoneyReason reason, String note) {
        if (amount > 0) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("amount", amount);
            b.put("moneyReason", reason.name());
            b.put("note", note);
            out.add(b);
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    /** The point of a delivery while valid (else the delivery goes to the shop place). */
    Optional<DealerDeliveryPoint> validPoint(Savegame sg, DealerDelivery d) {
        if (d.getDeliveryPointId() == null) {
            return Optional.empty();
        }
        return places.places(sg).points().stream()
                .filter(x -> x.point().getId().equals(d.getDeliveryPointId()) && x.valid())
                .map(DeliveryPointService.Point::point).findFirst();
    }

    /** The agreed delivery costs; an invalid point goes to the shop place for the base fee (owner decision). */
    public long effectiveFee(Savegame sg, DealerDelivery d) {
        return d.getDeliveryPointId() != null && validPoint(sg, d).isEmpty() ? cfg().getDeliveryBaseFee()
                : d.getDeliveryFee();
    }

    /** VEHICLE_DELIVERY of a planned or waiting delivery; {@code retry} = "Lieferung erneut versuchen" (fee). */
    @Transactional
    public void send(Savegame sg, DealerDelivery d, boolean retry) {
        List<DealerOrderItem> its = itemsOf(d);
        if (its.isEmpty()) {
            d.setStatus(DealerDelivery.CANCELLED);
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("deliveryId", "dlv_" + d.getId());
        List<Map<String, Object>> list = new ArrayList<>();
        for (DealerOrderItem i : its) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("storeXmlFilename", i.getXmlFilename());
            e.put("configurations", json.readValue(i.getOptionsJson(), OPTIONS));
            list.add(e);
        }
        p.put("items", list);
        validPoint(sg, d).ifPresent(pt -> {
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("x", pt.getX());
            place.put("z", pt.getZ());
            place.put("ry", pt.getRy());
            p.put("place", place);
        });
        p.put("fallbackToShop", true);
        List<Map<String, Object>> b = bookings(sg, d, retry);
        p.put("bookings", b);
        if (d.isCarriesTradeIns()) {
            p.put("tradeIns", tradeIns.findByOrderIdOrderByIdAsc(d.getOrderId()).stream()
                    .filter(t -> DealerTradeIn.OPEN.equals(t.getStatus())).map(DealerTradeIn::getVehicleId).toList());
        }
        var ins = outbox.vehicleDelivery(sg, p, new Related(RELATED_DELIVERY, d.getId()));
        d.setInstructionId(ins.getInstructionId());
        d.setSentAsRetry(retry);
        d.setSentFee(b.stream().mapToLong(x -> ((Number) x.get("amount")).longValue()).sum());
        d.setAttempts(d.getAttempts() + 1);
        d.setStatus(DealerDelivery.SENT);
    }

    /** L7: "Lieferung erneut versuchen" - at once (next bridge cycle) with the retry fee. */
    @Transactional
    public void retry(Savegame sg, long deliveryId) {
        DealerDelivery d = ownDelivery(sg, deliveryId);
        if (!DealerDelivery.WAITING.equals(d.getStatus())) {
            throw new BusinessRuleException("DEALER_NOT_WAITING", "Diese Lieferung wartet nicht beim Händler.");
        }
        send(sg, d, true);
    }

    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (e.relatedId() == null) {
            return;
        }
        if (RELATED_ORDER.equals(e.relatedType())) {
            orders.findById(e.relatedId()).filter(o -> DealerOrder.DEPOSIT_PENDING.equals(o.getStatus())).ifPresent(o -> {
                boolean money = instructions.findByInstructionId(e.instructionId())
                        .map(x -> x.getType() == InstructionType.MONEY_TRANSACTION).orElse(false);
                if (!money) {
                    return;
                }
                if ("APPLIED".equals(e.status())) {
                    ordered(o.getSavegame(), o);
                } else {
                    depositFailed(o.getSavegame(), o, instructions.findByInstructionId(e.instructionId())
                            .map(x -> x.getAckMessage()).orElse(null));
                }
            });
            return;
        }
        if (!RELATED_DELIVERY.equals(e.relatedType())) {
            return;
        }
        DealerDelivery d = deliveries.findById(e.relatedId()).orElse(null);
        if (d == null || !DealerDelivery.SENT.equals(d.getStatus()) || !e.instructionId().equals(d.getInstructionId())) {
            return;
        }
        Savegame sg = d.getSavegame();
        if ("APPLIED".equals(e.status())) {
            delivered(sg, d, e.result() == null ? Map.of() : e.result());
        } else {
            failed(sg, d, instructions.findByInstructionId(e.instructionId()).map(x -> x.getAckMessage()).orElse(null));
        }
    }

    private void depositFailed(Savegame sg, DealerOrder o, String message) {
        o.setStatus(DealerOrder.FAILED);
        o.setClosedGameTime(sg.getCurrentGameTime());
        closeDeliveries(o);
        narration.request(sg, NarrationEventType.DEALER_ORDER_FAILED).from(dealer(sg))
                .facts(NarrationFacts.builder().put("machines", names(items.findByOrderIdOrderByPositionAsc(o.getId())))
                        .put("reason", reason(message)).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED_ORDER, o.getId()).submit();
    }

    private void delivered(Savegame sg, DealerDelivery d, Map<String, Object> result) {
        List<DealerOrderItem> its = itemsOf(d);
        String placeUsed = String.valueOf(result.getOrDefault("placeUsed", "SHOP"));
        Optional<DealerDeliveryPoint> point = validPoint(sg, d);
        String where = "DELIVERY_POINT".equals(placeUsed) && point.isPresent() ? pointName(point.get()) : SHOP_PLACE;
        d.setStatus(DealerDelivery.DELIVERED);
        d.setPlaceUsed(placeUsed);
        d.setPlaceName(where);
        d.setDeliveredGameTime(sg.getCurrentGameTime());
        List<String> ids = new ArrayList<>();
        for (int k = 1; result.containsKey("vehicleId" + k); k++) {
            ids.add(String.valueOf(result.get("vehicleId" + k)));
        }
        d.setVehicleIds(String.join(",", ids));
        its.forEach(i -> i.setStatus(DealerOrderItem.DELIVERED));
        if (d.isCarriesTradeIns()) {
            tradeIns.findByOrderIdOrderByIdAsc(d.getOrderId()).stream()
                    .filter(t -> DealerTradeIn.OPEN.equals(t.getStatus())).forEach(t -> t.setStatus(DealerTradeIn.REMOVED));
        }
        String names = names(its);
        String shopReason = "";
        if (d.getDeliveryPointId() != null && !"DELIVERY_POINT".equals(placeUsed)) {
            shopReason = point.isEmpty() ? " Der Lieferort gehört nicht mehr zu Ihrem Hof, deshalb stehen sie auf dem Shop-Platz."
                    : " Am Lieferort war kein Platz frei, deshalb stehen sie auf dem Shop-Platz.";
        }
        List<Map<String, Object>> b = bookings(sg, d, d.isSentAsRetry());
        long rest = b.stream().filter(x -> MoneyReason.VEHICLE_NEW_PURCHASE.name().equals(x.get("moneyReason")))
                .mapToLong(x -> ((Number) x.get("amount")).longValue()).sum();
        hint(sg, d, "Deine neue " + names + " steht bei " + where);
        narration.request(sg, NarrationEventType.DEALER_DELIVERED).from(dealer(sg))
                .facts(NarrationFacts.builder().put("machines", names).put("place", where).put("shopReason", shopReason)
                        .put("rest", rest).put("fees", Math.max(0, nz(d.getSentFee()) - rest)).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED_DELIVERY, d.getId()).submit();
        diary.addAuto(sg, "NEGOTIATION", "Neumaschine geliefert: " + names, "Steht bei " + where + ".",
                RELATED_DELIVERY, d.getId());
        if (its.stream().mapToLong(DealerOrderItem::getListPrice).sum() >= cfg().getGossipFromListPrice()) {
            Optional<Character> teller = lookup.firstActive(sg, CharacterRole.VILLAGER);
            teller.ifPresent(t -> narration.request(sg, NarrationEventType.DEALER_DELIVERY_GOSSIP).from(t)
                    .facts(NarrationFacts.builder().put("machines", names).build())
                    .category(CommunicationCategory.VILLAGE_LIFE).submit());
        }
        DealerOrder o = orders.findById(d.getOrderId()).orElseThrow();
        if (deliveries.findByOrderIdOrderByIdAsc(o.getId()).stream().allMatch(x -> DealerDelivery.DELIVERED.equals(x.getStatus())
                || DealerDelivery.CANCELLED.equals(x.getStatus()))) {
            o.setStatus(DealerOrder.COMPLETED);
            o.setClosedGameTime(sg.getCurrentGameTime());
        }
    }

    /** A failed delivery waits at the dealer (mail at the first failure); a lost trade-in is dropped from it. */
    private void failed(Savegame sg, DealerDelivery d, String message) {
        String code = reason(message);
        d.setStatus(DealerDelivery.WAITING);
        d.setLastFailure(code);
        d.setLastFailureDetail(detail(sg, d, code, message));
        if (("TRADE_IN_NOT_FOUND".equals(code) || "TRADE_IN_NOT_OWN".equals(code)) && d.isCarriesTradeIns()) {
            Set<String> present = vehicles(sg).stream().map(BridgeDtos.Vehicle::uniqueId).collect(Collectors.toSet());
            for (DealerTradeIn t : tradeIns.findByOrderIdOrderByIdAsc(d.getOrderId())) {
                if (DealerTradeIn.OPEN.equals(t.getStatus()) && (!present.contains(t.getVehicleId())
                        || "TRADE_IN_NOT_OWN".equals(code) && message != null && message.contains(t.getVehicleId()))) {
                    t.setStatus(DealerTradeIn.LOST);
                    narration.request(sg, NarrationEventType.DEALER_TRADE_IN_LOST).from(dealer(sg))
                            .facts(NarrationFacts.builder().put("name", t.getName()).put("amount", t.getOfferValue()).build())
                            .category(CommunicationCategory.NEGOTIATION).related(RELATED_ORDER, d.getOrderId()).submit();
                }
            }
        }
        if (!d.isFailureMailed()) {
            d.setFailureMailed(true);
            hint(sg, d, "FarmPulse: " + names(itemsOf(d)) + " wartet beim Händler – " + reasonText(sg, d));
            narration.request(sg, NarrationEventType.DEALER_DELIVERY_FAILED).from(dealer(sg))
                    .facts(NarrationFacts.builder().put("machines", names(itemsOf(d))).put("reason", reasonText(sg, d))
                            .put("retryFee", cfg().getRetryFee()).build())
                    .category(CommunicationCategory.NEGOTIATION).related(RELATED_DELIVERY, d.getId())
                    .formLink("/haendler/bestellungen").submit();
        }
    }

    /** The mod's code (e.g. NO_SPACE, TRADE_IN_ATTACHED, "unknown type …" of an older mod -> MOD_OUTDATED). */
    static String reason(String message) {
        if (message == null || message.isBlank()) {
            return "UNKNOWN";
        }
        if (message.contains("unknown type") || message.contains("NOT_SUPPORTED")) {
            return "MOD_OUTDATED";
        }
        String m = message.strip();
        int colon = m.indexOf(':');
        return (colon > 0 ? m.substring(0, colon) : m).strip();
    }

    /**
     * Detail of the last failure for the tablet: the name of the trade-in (the mod reports "TRADE_IN_ATTACHED: &lt;uniqueId&gt;")
     * or the missing amount.
     */
    private String detail(Savegame sg, DealerDelivery d, String code, String message) {
        if (code.startsWith("TRADE_IN")) {
            String id = message == null || message.indexOf(':') < 0 ? null : message.substring(message.indexOf(':') + 1).strip();
            return tradeIns.findByOrderIdOrderByIdAsc(d.getOrderId()).stream()
                    .filter(t -> id == null ? DealerTradeIn.OPEN.equals(t.getStatus()) : t.getVehicleId().equals(id))
                    .map(DealerTradeIn::getName).collect(Collectors.joining(", "));
        }
        if ("INSUFFICIENT_FUNDS".equals(code)) {
            long balance = facts.latest(sg).map(f -> f.liquidity() == null ? 0L : Math.round(f.liquidity().balance()))
                    .orElse(0L);
            long floor = de.farmpulse.rpsim.bridge.LiquidityService.minBalance(sg);
            return String.valueOf(Math.max(0, nz(d.getSentFee()) - (balance - floor)));
        }
        return null;
    }

    /** Agreed reason texts of the tablet ("Shop-Platz belegt", "<Name> ist angekoppelt", ...). */
    public String reasonText(Savegame sg, DealerDelivery d) {
        String code = d.getLastFailure() == null ? "" : d.getLastFailure();
        String detail = d.getLastFailureDetail();
        return switch (code) {
            case "NO_SPACE" -> "Shop-Platz belegt";
            case "TRADE_IN_ATTACHED" -> detail + " ist angekoppelt";
            case "TRADE_IN_IN_USE" -> detail + " ist in Benutzung";
            case "INSUFFICIENT_FUNDS" -> "Es fehlen " + GameTimeText.money(Long.parseLong(detail == null ? "0" : detail));
            case "MOD_OUTDATED" -> "Mod aktualisieren";
            default -> code;
        };
    }

    private void hint(Savegame sg, DealerDelivery d, String text) {
        outbox.notification(sg, text, "INFO", sg.getCurrentGameTime() + GameTime.days(1),
                new Related(RELATED_DELIVERY, d.getId()));
    }

    // ------------------------------------------------------------------------------------------ B6 cancellation

    /** Refund of a cancellation: the deposit share x 50 % (100 % after a delay). */
    public long refund(DealerDelivery d, Collection<DealerOrderItem> its) {
        long deposit = its.stream().mapToLong(i -> nz(i.getDepositShare())).sum();
        double share = d.isDelayed() ? cfg().getCancelRefundShareDelayed() : cfg().getCancelRefundShare();
        return Math.round(deposit * share);
    }

    /**
     * "Stornieren": a delivery ("zusammen": the whole order, "einzeln": one position) until it is sent or delivered.
     * VEHICLE_REFUND (+ whole deposit) and VEHICLE_CANCELLATION_FEE (- kept part) as one batch.
     */
    @Transactional
    public void cancel(Savegame sg, long deliveryId) {
        DealerDelivery d = ownDelivery(sg, deliveryId);
        if (!DealerDelivery.PLANNED.equals(d.getStatus()) && !DealerDelivery.WAITING.equals(d.getStatus())) {
            throw new BusinessRuleException("DEALER_NOT_CANCELLABLE", "Diese Lieferung kann nicht mehr storniert werden.");
        }
        DealerOrder o = orders.findById(d.getOrderId()).orElseThrow();
        if (!DealerOrder.ORDERED.equals(o.getStatus())) {
            throw new BusinessRuleException("DEALER_NOT_CANCELLABLE", "Diese Lieferung kann nicht mehr storniert werden.");
        }
        List<DealerOrderItem> its = itemsOf(d);
        long deposit = its.stream().mapToLong(i -> nz(i.getDepositShare())).sum();
        long refund = refund(d, its);
        long kept = deposit - refund;
        String names = names(its);
        if (deposit > 0) {
            String batch = "batch_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            outbox.money(sg, deposit, MoneyReason.VEHICLE_REFUND, "Rückzahlung " + names,
                    new Related(RELATED_DELIVERY, d.getId()), batch, null);
            if (kept > 0) {
                outbox.money(sg, -kept, MoneyReason.VEHICLE_CANCELLATION_FEE, "Stornogebühr " + names,
                        new Related(RELATED_DELIVERY, d.getId()), batch, null);
            }
        }
        d.setStatus(DealerDelivery.CANCELLED);
        its.forEach(i -> i.setStatus(DealerOrderItem.CANCELLED));
        List<DealerDelivery> rest = deliveries.findByOrderIdOrderByIdAsc(o.getId()).stream()
                .filter(x -> !DealerDelivery.CANCELLED.equals(x.getStatus())).toList();
        if (d.isCarriesTradeIns()) {
            // the trade-ins go with the next delivery of the order (owner decision: with the first delivery)
            rest.stream().filter(x -> DealerDelivery.PLANNED.equals(x.getStatus()) || DealerDelivery.WAITING.equals(x.getStatus()))
                    .findFirst().ifPresent(x -> x.setCarriesTradeIns(true));
            d.setCarriesTradeIns(false);
        }
        if (rest.isEmpty()) {
            o.setStatus(DealerOrder.CANCELLED);
            o.setClosedGameTime(sg.getCurrentGameTime());
        } else if (rest.stream().allMatch(x -> DealerDelivery.DELIVERED.equals(x.getStatus()))) {
            o.setStatus(DealerOrder.COMPLETED);
            o.setClosedGameTime(sg.getCurrentGameTime());
        }
        narration.request(sg, NarrationEventType.DEALER_CANCELLED).from(dealer(sg))
                .facts(NarrationFacts.builder().put("machines", names).put("refund", refund).put("fee", kept).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED_ORDER, o.getId()).submit();
        diary.addAuto(sg, "NEGOTIATION", "Bestellung storniert: " + names,
                "Erstattet " + refund + " €, Stornogebühr " + kept + " €.", RELATED_ORDER, o.getId());
    }

    // ------------------------------------------------------------------------------------------ views

    @Transactional(readOnly = true)
    public List<DealerOrder> list(Savegame sg) {
        return orders.findBySavegameOrderByIdDesc(sg);
    }

    public List<DealerDelivery> deliveriesOf(DealerOrder o) {
        return deliveries.findByOrderIdOrderByIdAsc(o.getId());
    }

    public List<DealerOrderItem> itemsOf(DealerOrder o) {
        return items.findByOrderIdOrderByPositionAsc(o.getId());
    }

    public List<DealerTradeIn> tradeInsOf(DealerOrder o) {
        return tradeIns.findByOrderIdOrderByIdAsc(o.getId());
    }

    public Optional<Negotiation> negotiationOf(DealerOrder o) {
        return o.getNegotiationId() == null ? Optional.empty() : negotiations.findById(o.getNegotiationId());
    }

    /** The open deliveries with date and rest (calendar, liquidity plan, tile). */
    @Transactional(readOnly = true)
    public List<DealerDelivery> planned(Savegame sg) {
        return deliveries.findBySavegameAndStatusIn(sg, List.of(DealerDelivery.PLANNED, DealerDelivery.WAITING)).stream()
                .filter(d -> d.getDueGameTime() != null).toList();
    }

    /** What a planned delivery will book (rest, delivery costs, express). */
    @Transactional(readOnly = true)
    public long due(Savegame sg, DealerDelivery d) {
        return bookings(sg, d, false).stream().mapToLong(x -> ((Number) x.get("amount")).longValue()).sum();
    }

    /** Factor of the delivery times (days of a 30-day month -> game days of the savegame). */
    @Transactional(readOnly = true)
    public double dayFactor(Savegame sg) {
        return AbsenceScale.factor(absence.daysPerPeriod(sg), props.getFormulas().getAbsenceScaling());
    }

    /** The rest of the machines a delivery books (VEHICLE_NEW_PURCHASE, with trade-ins lost or damaged). */
    @Transactional(readOnly = true)
    public long rest(Savegame sg, DealerDelivery d) {
        return bookings(sg, d, false).stream()
                .filter(x -> MoneyReason.VEHICLE_NEW_PURCHASE.name().equals(x.get("moneyReason")))
                .mapToLong(x -> ((Number) x.get("amount")).longValue()).sum();
    }

    /** What the player still pays for an order (offer: price + delivery costs + express; order: the open deliveries). */
    @Transactional(readOnly = true)
    public long openAmount(Savegame sg, DealerOrder o, long price) {
        List<DealerDelivery> ds = deliveries.findByOrderIdOrderByIdAsc(o.getId());
        if (DealerOrder.REQUESTED.equals(o.getStatus()) || DealerOrder.OFFERED.equals(o.getStatus())) {
            long fees = ds.stream().mapToLong(d -> effectiveFee(sg, d)).sum();
            long express = items.findByOrderIdOrderByPositionAsc(o.getId()).stream().mapToLong(DealerOrderItem::getExpressFee)
                    .sum();
            return price + fees + express;
        }
        return ds.stream().filter(d -> DealerDelivery.PLANNED.equals(d.getStatus()) || DealerDelivery.WAITING.equals(d.getStatus())
                || DealerDelivery.SENT.equals(d.getStatus())).mapToLong(d -> due(sg, d)).sum();
    }

    /** The current price of the dealer: his last counter offer, else the offer price. */
    public static long currentPrice(DealerOrder o, Negotiation n) {
        return n != null && n.getLastCounterOffer() != null ? n.getLastCounterOffer() : o.getOfferPrice() == null ? 0
                : o.getOfferPrice();
    }

    public String names(List<DealerOrderItem> its) {
        return its.stream().map(DealerOrderItem::getName).collect(Collectors.joining(", "));
    }

    public String placeName(Long pointId) {
        return pointId == null ? SHOP_PLACE : points.findById(pointId).map(this::pointName).orElse(SHOP_PLACE);
    }

    String pointName(DealerDeliveryPoint p) {
        return p.getName() != null ? p.getName() : "Lieferort " + p.getSeq();
    }

    DealerOrder own(Savegame sg, long id) {
        return orders.findById(id).filter(o -> o.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("Bestellung nicht gefunden: " + id));
    }

    DealerDelivery ownDelivery(Savegame sg, long id) {
        return deliveries.findById(id).filter(d -> d.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("Lieferung nicht gefunden: " + id));
    }

    static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
