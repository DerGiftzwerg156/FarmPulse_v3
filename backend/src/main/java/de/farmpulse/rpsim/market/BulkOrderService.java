package de.farmpulse.rpsim.market;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.BulkOrder;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.neighbor.NeighborService;
import de.farmpulse.rpsim.neighbor.NeighborTradeService;
import de.farmpulse.rpsim.repository.BulkOrderRepository;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.repository.ForwardContractRepository;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.CalendarChangedEvent;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.2 R32-G: bulk orders of bulk buyers (owner decisions 2026-10-08 in QUESTIONS.md).
 * <ul>
 *   <li>G1 Monthly: up to max-requests-per-month requests, each with probability x the refusal factor of the savegame.
 *   A request names a configured fill type, an amount from its fixed range and a sell point of the map that accepts the
 *   fill type (no production). Every request brings a new bulk buyer ({@code BULK_BUYER}) of that sell point, who is
 *   retired when the order ends. It comes as a mail, call-share of them as a call; a missed or declined call keeps the
 *   topic open and the request also comes as a mail. Unanswered until the deadline = refused.</li>
 *   <li>G2 "Sofort liefern" from the own silos when the whole amount lies there, at best market price x
 *   instant-markup (fixed in the request): {@code STORAGE_TRANSFER OUT} + {@code MONEY_TRANSACTION GOODS_SALE} like
 *   the farm shop. A refused transfer books nothing and leaves the request open.</li>
 *   <li>G3 "Termin vereinbaren" until the answer deadline: the whole amount in a delivery month lead months ahead at
 *   today's price of the buyer's sell point x (1 + term-base-markup + term-markup-per-month x lead), sent as
 *   {@code PRICE_EVENT / FIXED} for that whole month (like the forward contract) with a NOTIFICATION at its start.
 *   Months in which a forward contract, another bulk order or a special offer holds a fixed price at the pair cannot
 *   be chosen; at most max-open such orders.</li>
 *   <li>G4 The mod's contract report settles it: full delivery = trust +, factor one step up; shortfall = shortfall x
 *   fixed price x penalty-share as {@code CONTRACT_PENALTY}, trust -, factor one step down.</li>
 * </ul>
 * "Tage je Periode" changed: an order whose price instruction is still pending keeps its month (start and end are
 * moved); in a running delivery month the mod keeps the end it has (owner decision 2026-10-08).
 */
@Service
public class BulkOrderService {

    /** Related type of the request (service case BULK_ORDER). */
    public static final String RELATED = "BULK_ORDER";
    /** Related type of an order with a delivery month (bulk_order). */
    public static final String ORDER_RELATED = "BULK_ORDER_TERM";

    private final ServiceCaseRepository cases;
    private final BulkOrderRepository orders;
    private final ForwardContractRepository forwardContracts;
    private final MarketEventRepository marketEvents;
    private final SavegameRepository savegames;
    private final CommunicationRepository communications;
    private final FactsService facts;
    private final NeighborService neighbors;
    private final NeighborTradeService trade;
    private final CharacterGeneratorService generator;
    private final VillageReputationService reputation;
    private final TrustScoreService trust;
    private final OutboxService outbox;
    private final OutboxInstructionRepository instructions;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final FallbackTemplates labels;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public BulkOrderService(ServiceCaseRepository cases, BulkOrderRepository orders,
                            ForwardContractRepository forwardContracts, MarketEventRepository marketEvents,
                            SavegameRepository savegames, CommunicationRepository communications, FactsService facts,
                            NeighborService neighbors, NeighborTradeService trade, CharacterGeneratorService generator,
                            VillageReputationService reputation, TrustScoreService trust, OutboxService outbox,
                            OutboxInstructionRepository instructions, NarrationRequestService narration,
                            DiaryService diary, FallbackTemplates labels, RandomSource random, RpsimProperties props,
                            GameTime gameTime) {
        this.cases = cases;
        this.orders = orders;
        this.forwardContracts = forwardContracts;
        this.marketEvents = marketEvents;
        this.savegames = savegames;
        this.communications = communications;
        this.facts = facts;
        this.neighbors = neighbors;
        this.trade = trade;
        this.generator = generator;
        this.reputation = reputation;
        this.trust = trust;
        this.outbox = outbox;
        this.instructions = instructions;
        this.narration = narration;
        this.diary = diary;
        this.labels = labels;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.BulkOrder cfg() {
        return props.getFormulas().getBulkOrder();
    }

    // ------------------------------------------------------------------------------------------ formulas

    /** G2: instant price per 1000 l = best market price of all sell points x instant-markup; empty without a price. */
    Optional<Long> instantUnitPrice(FarmFacts f, String fillType) {
        if (f == null || f.prices() == null) {
            return Optional.empty();
        }
        return f.prices().stream().filter(p -> fillType.equals(p.fillType()) && p.currentPrice() != null
                        && p.currentPrice() > 0)
                .map(BridgeDtos.Price::currentPrice).max(Double::compare)
                .map(best -> Math.round(best * cfg().getInstantMarkup()));
    }

    /** G3: fixed price per 1000 l = today's price of the sell point x (1 + base markup + markup per month x lead). */
    long termUnitPrice(double sellPointPrice, int leadMonths) {
        return Math.round(sellPointPrice * (1 + cfg().getTermBaseMarkup() + cfg().getTermMarkupPerMonth() * leadMonths));
    }

    /** G4: shortfall x fixed price x penalty-share (EUR). */
    long penalty(long shortfall, long fixedPrice) {
        return Math.round(shortfall / 1000.0 * fixedPrice * cfg().getPenaltyShare());
    }

    /** Today's price of a fill type at a sell point (EUR per 1000 l), 0 without a price. */
    static double sellPointPrice(FarmFacts f, String sellPoint, String fillType) {
        if (f == null || f.prices() == null) {
            return 0;
        }
        return f.prices().stream().filter(p -> sellPoint.equals(p.sellPoint()) && fillType.equals(p.fillType())
                        && p.currentPrice() != null)
                .mapToDouble(BridgeDtos.Price::currentPrice).findFirst().orElse(0);
    }

    // ------------------------------------------------------------------------------------------ G1 requests

    @EventListener
    @Order(84)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled()) {
            return;
        }
        double p = cfg().getProbability() * sg.getBulkOrderFactor();
        for (int i = 0; i < cfg().getMaxRequestsPerMonth(); i++) {
            if (random.chance(p)) {
                spawnRequest(sg);
            }
        }
    }

    record Candidate(String fillType, BridgeDtos.SellPoint sellPoint, RpsimProperties.BulkOrder.Amount amount) {
    }

    /** Sell points of the map (no production) that accept a configured fill type and name a price for it. */
    List<Candidate> candidates(FarmFacts f, MarketContext ctx) {
        List<Candidate> list = new ArrayList<>();
        if (f == null || ctx == null || ctx.sellPoints() == null) {
            return list;
        }
        for (String fillType : cfg().getFillTypes()) {
            RpsimProperties.BulkOrder.Amount amount = cfg().getAmounts().get(fillType);
            if (amount == null || amount.getMax() < amount.getMin() || amount.getStep() <= 0) {
                continue;
            }
            for (BridgeDtos.SellPoint sp : ctx.sellPoints()) {
                if (!Boolean.TRUE.equals(sp.production()) && sp.acceptedFillTypes() != null
                        && sp.acceptedFillTypes().contains(fillType) && sellPointPrice(f, sp.id(), fillType) > 0) {
                    list.add(new Candidate(fillType, sp, amount));
                }
            }
        }
        return list;
    }

    /** One request of a new bulk buyer (empty when no sell point fits). */
    @Transactional
    public Optional<ServiceCase> spawnRequest(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        MarketContext ctx = facts.marketContext(sg).orElse(null);
        List<Candidate> list = candidates(f, ctx);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Candidate c = random.pick(list);
        Optional<Long> unit = instantUnitPrice(f, c.fillType());
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        long step = c.amount().getStep();
        int steps = (int) ((c.amount().getMax() - c.amount().getMin()) / step);
        long liters = c.amount().getMin() + step * random.intBetween(0, Math.max(0, steps));
        Character buyer = newBuyer(sg, c.sellPoint());
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.BULK_ORDER);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(buyer);
        sc.setReference(c.fillType());
        sc.setExternalId(c.sellPoint().id());
        sc.setTitle(c.sellPoint().name());
        sc.setQuantity((int) liters);
        sc.setCostAmount(unit.get());
        sc.setOfferAmount(Math.round(liters / 1000.0 * unit.get()));
        sc.setDirection("SELL");
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getAnswerDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        requestMessage(sg, sc, random.chance(cfg().getCallShare()) ? Channel.CALL : Channel.MAIL);
        return Optional.of(sc);
    }

    /** A new bulk buyer of the sell point; the generator names the contact person (owner decision: one per request). */
    private Character newBuyer(Savegame sg, BridgeDtos.SellPoint sp) {
        Character c = generator.generate(sg, CharacterGeneratorService.Spec.of(CharacterRole.BULK_BUYER,
                CharacterCategory.MANDATORY, reputation.baseTrustForNewCharacter(sg)), random.nextLong());
        c.setAffiliation(sp.name());
        c.setShortDescription(c.getName() + ", Einkauf " + sp.name() + " – bestellt Ware in großen Mengen. Gilt als "
                + c.getTraits() + ".");
        c.setBackstory(c.getShortDescription());
        generator.enrich(c, "Einkäufer/in von " + sp.name());
        return c;
    }

    private void requestMessage(Savegame sg, ServiceCase sc, Channel channel) {
        narration.request(sg, NarrationEventType.BULK_ORDER_REQUEST).from(sc.getCharacter()).channel(channel)
                .facts(NarrationFacts.builder().put("fillType", sc.getReference()).put("amount", sc.getQuantity())
                        .put("sellPoint", sc.getTitle()).put("instantUnitPrice", sc.getCostAmount())
                        .put("instantPrice", sc.getOfferAmount())
                        .put("answerDays", Math.round(cfg().getAnswerDays())).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).formLink("/handel?case=" + sc.getId())
                .submit();
    }

    /** G1: a missed or declined call keeps the topic open; the request also comes as a mail while it is open. */
    @EventListener
    @Transactional
    public void onCall(CallService.CallStatusChanged e) {
        if (e.status() != CallStatus.MISSED && e.status() != CallStatus.DECLINED) {
            return;
        }
        communications.findById(e.communicationId())
                .filter(c -> RELATED.equals(c.getRelatedEntityType()) && c.getRelatedEntityId() != null)
                .flatMap(c -> cases.findById(c.getRelatedEntityId()))
                .filter(sc -> sc.getKind() == CaseKind.BULK_ORDER && sc.getStatus() == CaseStatus.AWAITING_PLAYER)
                .ifPresent(sc -> requestMessage(sc.getSavegame(), sc, Channel.MAIL));
    }

    private ServiceCase open(Savegame sg, Long id) {
        ServiceCase sc = cases.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != CaseKind.BULK_ORDER || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Diese Anfrage ist nicht mehr offen.");
        }
        return sc;
    }

    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        close(sc, CaseStatus.DECLINED, "PLAYER");
        lowerFactor(sg);
        retire(sc.getCharacter());
        return sc;
    }

    /** Daily: a request left unanswered until its deadline counts like a refusal. */
    @EventListener
    @Order(82)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() == CaseKind.BULK_ORDER && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
                close(sc, CaseStatus.EXPIRED, "NO_ANSWER");
                lowerFactor(sg);
                retire(sc.getCharacter());
            }
        }
    }

    // ------------------------------------------------------------------------------------------ G2 instant delivery

    /** "Sofort liefern": the whole amount from the own silos, checked against the stock again. */
    @Transactional
    public ServiceCase deliver(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.tradeStorage() == null) {
            throw new BusinessRuleException("TRADE_NO_SILOS", "Der Mod meldet deine Silos nicht – bitte den Mod "
                    + "FS25_RPSim aktualisieren.");
        }
        long liters = sc.getQuantity();
        if (trade.playerStock(sg, neighbors.tradeStorage(f), sc.getReference()) < liters) {
            throw new BusinessRuleException("TRADE_NO_STOCK", "In deinen Silos liegt nicht genug "
                    + labels.label(sc.getReference()) + " (" + liters(liters) + " l). Du kannst einen Termin vereinbaren.");
        }
        outbox.storageDeal(sg, false, sc.getReference(), liters, sc.getOfferAmount(), MoneyReason.GOODS_SALE,
                "Großauftrag: " + liters(liters) + " l " + labels.label(sc.getReference()) + " an " + sc.getTitle(),
                new Related(RELATED, sc.getId()));
        sc.setStatus(CaseStatus.IN_PROGRESS);
        return sc;
    }

    /** The mod took the goods out of the silo (the money part of the batch was booked with it). */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        boolean transfer = instructions.findByInstructionId(e.instructionId())
                .map(o -> o.getType() == InstructionType.STORAGE_TRANSFER).orElse(false);
        ServiceCase sc = cases.findById(e.relatedId()).orElse(null);
        if (!transfer || sc == null || sc.getKind() != CaseKind.BULK_ORDER || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return;
        }
        Savegame sg = sc.getSavegame();
        close(sc, CaseStatus.SETTLED, "DELIVERED");
        raiseFactor(sg);
        String what = labels.label(sc.getReference());
        trust.recordEvent(sc.getCharacter(), cfg().getFulfilledTrustDelta(), TrustReason.BULK_ORDER_FULFILLED, what);
        narration.request(sg, NarrationEventType.BULK_ORDER_DELIVERED).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("fillType", sc.getReference()).put("amount", sc.getQuantity())
                        .put("sellPoint", sc.getTitle()).put("price", sc.getOfferAmount()).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "TRADE", "Großauftrag geliefert", liters(sc.getQuantity()) + " l " + what + " an "
                + sc.getTitle() + " verkauft (" + sc.getOfferAmount() + " €).", RELATED, sc.getId());
        retire(sc.getCharacter());
    }

    /** FailedInstructionService: the mod did not take the goods - nothing booked, the request stays open. */
    @Transactional
    public boolean onInstructionFailed(Long caseId, String message) {
        ServiceCase sc = cases.findById(caseId).orElse(null);
        if (sc == null || sc.getKind() != CaseKind.BULK_ORDER || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return false;
        }
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        return true;
    }

    // ------------------------------------------------------------------------------------------ G3 delivery month

    /** One selectable delivery month of a request. available = false names the reason (busy pair or no price). */
    public record MonthOption(int leadMonths, long monthIndex, Integer period, long startGameTime, long deadlineGameTime,
                              long fixedPrice, long expectedIncome, boolean available, String reason) {
    }

    /** The delivery months of a request with their fixed price (G3). */
    @Transactional(readOnly = true)
    public List<MonthOption> months(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        FarmFacts f = facts.latest(sg).orElse(null);
        double base = sellPointPrice(f, sc.getExternalId(), sc.getReference());
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long current = anchor.monthIndex(sg.getCurrentGameTime());
        boolean hasCalendar = f != null && f.calendar() != null;
        Set<Long> busy = busyMonths(sg, sc.getExternalId(), sc.getReference());
        List<MonthOption> out = new ArrayList<>();
        for (int lead = cfg().getMinLeadMonths(); lead <= cfg().getMaxLeadMonths(); lead++) {
            long idx = current + lead;
            long fixed = base > 0 ? termUnitPrice(base, lead) : 0;
            String reason = base <= 0 ? "NO_PRICE" : busy.contains(idx) ? "FIXED_PRICE_BUSY" : null;
            out.add(new MonthOption(lead, idx, hasCalendar ? anchor.periodOf(idx) : null, anchor.monthStart(idx),
                    anchor.monthStart(idx + 1), fixed, Math.round(sc.getQuantity() / 1000.0 * fixed), reason == null,
                    reason));
        }
        return out;
    }

    /** "Termin vereinbaren": binding, no withdrawal; the request is done with it. */
    @Transactional
    public BulkOrder agree(Savegame sg, Long id, int leadMonths) {
        ServiceCase sc = open(sg, id);
        if (leadMonths < cfg().getMinLeadMonths() || leadMonths > cfg().getMaxLeadMonths()) {
            throw new BusinessRuleException("BULK_ORDER_LEAD", "Der Liefermonat muss " + cfg().getMinLeadMonths()
                    + " bis " + cfg().getMaxLeadMonths() + " Monate voraus liegen.");
        }
        if (orders.findBySavegameAndStatus(sg, BulkOrder.OPEN).size() >= cfg().getMaxOpen()) {
            throw new BusinessRuleException("BULK_ORDER_LIMIT", "Es sind höchstens " + cfg().getMaxOpen()
                    + " offene Großaufträge mit Liefermonat möglich.");
        }
        MonthOption m = months(sg, id).stream().filter(o -> o.leadMonths() == leadMonths).findFirst().orElseThrow();
        if ("NO_PRICE".equals(m.reason())) {
            throw new BusinessRuleException("BULK_ORDER_NO_PRICE", sc.getTitle() + " nennt für "
                    + labels.label(sc.getReference()) + " gerade keinen Preis.");
        }
        if (!m.available()) {
            throw new BusinessRuleException("BULK_ORDER_MONTH_BUSY", "In diesem Monat gilt für "
                    + labels.label(sc.getReference()) + " an " + sc.getTitle() + " schon ein Festpreis.");
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        BulkOrder o = new BulkOrder();
        o.setSavegame(sg);
        o.setCaseId(sc.getId());
        o.setCharacter(sc.getCharacter());
        o.setFillType(sc.getReference());
        o.setSellPoint(sc.getExternalId());
        o.setQuantity(sc.getQuantity());
        o.setFixedPrice(m.fixedPrice());
        o.setBasePrice(sellPointPrice(f, sc.getExternalId(), sc.getReference()));
        o.setLeadMonths(leadMonths);
        o.setDeliveryStartGameTime(m.startGameTime());
        o.setDeadlineGameTime(m.deadlineGameTime());
        o.setStatus(BulkOrder.OPEN);
        o.setCreatedGameTime(sg.getCurrentGameTime());
        orders.save(o);
        Related related = new Related(ORDER_RELATED, o.getId());
        o.setInstructionId(outbox.priceFixed(sg, o.getFillType(), o.getSellPoint(), o.getFixedPrice(), o.getQuantity(),
                o.getDeadlineGameTime(), o.getDeliveryStartGameTime(), related).getInstructionId());
        o.setNoticeInstructionId(outbox.notification(sg, noticeText(sc), "INFO", o.getDeadlineGameTime(),
                o.getDeliveryStartGameTime(), related).getInstructionId());
        close(sc, CaseStatus.SETTLED, "TERM_AGREED");
        diary.addAuto(sg, "TRADE", "Großauftrag vereinbart", liters(o.getQuantity()) + " l "
                + labels.label(o.getFillType()) + " an " + sc.getTitle() + " zu " + o.getFixedPrice()
                + " € je 1.000 l, Lieferung in " + leadMonths + " Monat(en).", ORDER_RELATED, o.getId());
        return o;
    }

    /** In-game hint at the start of the delivery month, e.g. "Ölmühle Nord wartet diesen Monat auf 500.000 l Raps". */
    String noticeText(ServiceCase sc) {
        String text = "FarmPulse: " + sc.getTitle() + " wartet diesen Monat auf " + liters(sc.getQuantity()) + " l "
                + labels.label(sc.getReference());
        return text.length() <= 120 ? text : text.substring(0, 119) + "…";
    }

    /** Month indices in which the pair already carries a fixed price (forward contract, bulk order, special offer). */
    Set<Long> busyMonths(Savegame sg, String sellPoint, String fillType) {
        GameTime.Anchor anchor = gameTime.anchor(sg);
        Set<Long> busy = new HashSet<>();
        for (ForwardContract fc : forwardContracts.findBySavegameAndStatus(sg, ForwardContract.OPEN)) {
            if (sellPoint.equals(fc.getSellPoint()) && fillType.equals(fc.getFillType())) {
                busy.add(anchor.monthIndex(fc.getDeliveryStartGameTime()));
            }
        }
        for (BulkOrder o : orders.findBySavegameAndStatus(sg, BulkOrder.OPEN)) {
            if (sellPoint.equals(o.getSellPoint()) && fillType.equals(o.getFillType())) {
                busy.add(anchor.monthIndex(o.getDeliveryStartGameTime()));
            }
        }
        for (MarketEvent ev : marketEvents.findBySavegameAndStatusIn(sg, MarketEventEngine.OPEN)) {
            if (ev.getEventType() == MarketEventType.SPECIAL_OFFER && ev.getStatus() != MarketEventStatus.PLANNED
                    && sellPoint.equals(ev.getSellPoint()) && fillType.equals(ev.getFillType())) {
                long end = ev.getEndGameTime() != null ? ev.getEndGameTime() : ev.getStartGameTime();
                for (long i = anchor.monthIndex(ev.getStartGameTime()); i <= anchor.monthIndex(Math.max(end - 1,
                        ev.getStartGameTime())); i++) {
                    busy.add(i);
                }
            }
        }
        return busy;
    }

    /** "sellPoint|fillType" pairs with an open order with a delivery month (one fixed price per pair, R3-M2). */
    public Set<String> openPairs(Savegame sg) {
        Set<String> s = new HashSet<>();
        orders.findBySavegameAndStatus(sg, BulkOrder.OPEN).forEach(o -> s.add(o.getSellPoint() + "|" + o.getFillType()));
        return s;
    }

    // ------------------------------------------------------------------------------------------ G4 settlement

    /** The mod reports the end of the FIXED contract with the delivered quantity. */
    @EventListener
    @Transactional
    public void onContractReported(BridgeEvents.ContractReported r) {
        BulkOrder o = orders.findByInstructionId(r.instructionId()).orElse(null);
        if (o == null || !BulkOrder.OPEN.equals(o.getStatus())) {
            return;
        }
        Savegame sg = o.getSavegame();
        long delivered = Math.min(r.deliveredQuantity(), o.getQuantity());
        long shortfall = o.getQuantity() - delivered;
        o.setDeliveredQuantity(delivered);
        o.setEndReason(r.endReason());
        Character buyer = o.getCharacter();
        String what = labels.label(o.getFillType());
        String where = sellPointName(sg, o.getSellPoint());
        NarrationFacts.Builder f = NarrationFacts.builder().put("fillType", o.getFillType()).put("sellPoint", where)
                .put("quantity", o.getQuantity()).put("delivered", delivered).put("fixedPrice", o.getFixedPrice());
        Related related = new Related(ORDER_RELATED, o.getId());
        if (shortfall <= 0) {
            o.setStatus(BulkOrder.FULFILLED);
            raiseFactor(sg);
            if (buyer != null) {
                trust.recordEvent(buyer, cfg().getFulfilledTrustDelta(), TrustReason.BULK_ORDER_FULFILLED, what);
            }
            narration.request(sg, NarrationEventType.BULK_ORDER_FULFILLED).from(buyer).facts(f.build())
                    .category(CommunicationCategory.TRADE).related(related.type(), related.id()).submit();
            diary.addAuto(sg, "TRADE", "Großauftrag erfüllt", liters(o.getQuantity()) + " l " + what + " an " + where
                    + " vollständig zum Festpreis geliefert.", related.type(), related.id());
        } else {
            long penalty = penalty(shortfall, o.getFixedPrice());
            o.setStatus(BulkOrder.SHORTFALL);
            o.setPenalty(penalty);
            lowerFactor(sg);
            if (penalty > 0) {
                outbox.money(sg, -penalty, MoneyReason.CONTRACT_PENALTY, "Fehlmenge Großauftrag " + what, related);
            }
            if (buyer != null) {
                trust.recordEvent(buyer, cfg().getShortfallTrustDelta(), TrustReason.BULK_ORDER_SHORTFALL, what);
            }
            narration.request(sg, NarrationEventType.BULK_ORDER_SHORTFALL).from(buyer)
                    .facts(f.put("shortfall", shortfall).put("penalty", penalty).build())
                    .category(CommunicationCategory.TRADE).related(related.type(), related.id()).submit();
            diary.addAuto(sg, "TRADE", "Großauftrag nicht erfüllt", liters(delivered) + " von "
                    + liters(o.getQuantity()) + " l " + what + " an " + where + " geliefert, Strafe " + penalty + " €.",
                    related.type(), related.id());
        }
        retire(buyer);
    }

    // ------------------------------------------------------------------------------------------ calendar

    /**
     * "Tage je Periode" changed: an order whose price instruction the mod has not taken yet keeps its delivery month -
     * start, end and the pending instructions move to the new month boundaries. In a running delivery month the mod
     * keeps its end (owner decision 2026-10-08).
     */
    @EventListener
    @Transactional
    public void onCalendarChanged(CalendarChangedEvent e) {
        for (BulkOrder o : orders.findBySavegame_IdAndStatus(e.savegameId(), BulkOrder.OPEN)) {
            long start = e.remap(o.getDeliveryStartGameTime());
            long deadline = e.remap(o.getDeadlineGameTime());
            Map<String, Object> price = new LinkedHashMap<>();
            price.put("deadlineGameTime", deadline);
            if (outbox.reschedulePending(o.getInstructionId(), start, price)) {
                o.setDeliveryStartGameTime(start);
                o.setDeadlineGameTime(deadline);
                if (o.getNoticeInstructionId() != null) {
                    Map<String, Object> notice = new LinkedHashMap<>();
                    notice.put("expiresAtGameTime", deadline);
                    outbox.reschedulePending(o.getNoticeInstructionId(), start, notice);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    void lowerFactor(Savegame sg) {
        sg.setBulkOrderFactor(Math.max(cfg().getMinFactor(), sg.getBulkOrderFactor() * cfg().getRefusalFactor()));
    }

    void raiseFactor(Savegame sg) {
        sg.setBulkOrderFactor(Math.min(1.0, sg.getBulkOrderFactor() / cfg().getRefusalFactor()));
    }

    private void close(ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sc.getSavegame().getCurrentGameTime());
    }

    /** The bulk buyer of one order leaves when the order ends (owner decision 2026-10-08, no farewell mail). */
    private void retire(Character c) {
        if (c == null || c.getStatus() == CharacterStatus.TERMINATED) {
            return;
        }
        c.setStatus(CharacterStatus.TERMINATED);
        c.setTerminationReason(TerminationReason.CONTRACT_ENDED);
        c.setLeftAtGameTime(c.getSavegame().getCurrentGameTime());
    }

    static String liters(long liters) {
        return String.format(Locale.GERMANY, "%,d", liters);
    }

    public String sellPointName(Savegame sg, String id) {
        return facts.marketContext(sg).flatMap(c -> c.sellPoints().stream().filter(s -> s.id().equals(id)).findFirst())
                .map(BridgeDtos.SellPoint::name).orElse(id);
    }

    public List<ServiceCase> requests(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.BULK_ORDER));
    }

    public List<BulkOrder> list(Savegame sg) {
        return orders.findBySavegameOrderByIdDesc(sg);
    }

    /** Open orders with a delivery month (liquidity plan K2: expected income in the delivery month, calendar). */
    public List<BulkOrder> open(Savegame sg) {
        return orders.findBySavegameAndStatus(sg, BulkOrder.OPEN);
    }
}
