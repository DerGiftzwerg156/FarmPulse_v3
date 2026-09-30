package de.farmpulse.rpsim.neighbor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.NeighborStock;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-H3 / R3-H4: trade with the neighbours. The goods move for real in the player's own silos
 * ({@code STORAGE_TRANSFER} + {@code MONEY_TRANSACTION} as one batch), stock and needs of the neighbours are backend
 * fiction (R3-H2), every price comes from the backend.
 * <ul>
 *   <li>H3: a neighbour offers goods of his stock (on his own, or asked by the player on the page "Handel"); only goods
 *   with free capacity in the own silos. The player buys: IN + GOODS_PURCHASE.</li>
 *   <li>H4: a neighbour asks for goods the player has in his silos (from the needs of his role). The player sells:
 *   OUT + GOODS_SALE; too little in the silo when executed = disappointed neighbour.</li>
 * </ul>
 * Offers and requests are service cases (status AWAITING_PLAYER until accepted, IN_PROGRESS until the mod acknowledged
 * the transfer). Declining costs a little trust, ignoring until the deadline more; a done trade gains trust, a fulfilled
 * request of a neighbour village reputation (capped per FS25 year).
 */
@Service
public class NeighborTradeService {

    public static final String RELATED = "NEIGHBOR_TRADE";
    /** title of a case: offer the player asked for (form) / offer or request of the neighbour on his own. */
    public static final String PLAYER_REQUEST = "PLAYER_REQUEST";
    public static final String NEIGHBOR_INITIATIVE = "NEIGHBOR";
    static final Set<CaseKind> KINDS = EnumSet.of(CaseKind.GOODS_OFFER, CaseKind.GOODS_REQUEST);

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final NeighborService neighbors;
    private final OutboxService outbox;
    private final OutboxInstructionRepository instructions;
    private final LiquidityService liquidity;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final PublicActionService publicActions;
    private final PublicActionEventRepository publicEvents;
    private final FallbackTemplates labels;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public NeighborTradeService(ServiceCaseRepository cases, SavegameRepository savegames, CharacterRepository characters,
                                NeighborService neighbors, OutboxService outbox, OutboxInstructionRepository instructions,
                                LiquidityService liquidity, TrustScoreService trust, NarrationRequestService narration,
                                DiaryService diary, PublicActionService publicActions,
                                PublicActionEventRepository publicEvents, FallbackTemplates labels, RandomSource random,
                                RpsimProperties props, GameTime gameTime) {
        this.cases = cases;
        this.savegames = savegames;
        this.characters = characters;
        this.neighbors = neighbors;
        this.outbox = outbox;
        this.instructions = instructions;
        this.liquidity = liquidity;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.publicActions = publicActions;
        this.publicEvents = publicEvents;
        this.labels = labels;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.NeighborTrade cfg() {
        return props.getFormulas().getNeighborTrade();
    }

    // ------------------------------------------------------------------------------------------ amounts

    /** Largest multiple of amount-step not above {@code max}, capped at amount-max; 0 below amount-min. */
    long maxAmount(double max) {
        long step = cfg().getAmountStep();
        long capped = (long) Math.floor(Math.min(max, cfg().getAmountMax()) / step) * step;
        return capped < cfg().getAmountMin() ? 0 : capped;
    }

    /** Random multiple of amount-step between amount-min and {@code max}. */
    long randomAmount(long max) {
        long step = cfg().getAmountStep();
        int steps = (int) ((max - cfg().getAmountMin()) / step);
        return cfg().getAmountMin() + step * random.intBetween(0, Math.max(0, steps));
    }

    /** Litres already on the way into / out of the own silos (accepted, not yet acknowledged). */
    long reserved(Savegame sg, CaseKind kind, String fillType) {
        return cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS).stream()
                .filter(c -> c.getKind() == kind && fillType.equals(c.getReference()))
                .mapToLong(c -> c.getQuantity() == null ? 0 : c.getQuantity()).sum();
    }

    long freeCapacity(Savegame sg, Map<String, BridgeDtos.TradeStorageEntry> ts, String fillType) {
        BridgeDtos.TradeStorageEntry e = ts.get(fillType);
        double free = e == null || e.freeCapacity() == null ? 0 : e.freeCapacity();
        return (long) Math.floor(free) - reserved(sg, CaseKind.GOODS_OFFER, fillType);
    }

    long playerStock(Savegame sg, Map<String, BridgeDtos.TradeStorageEntry> ts, String fillType) {
        BridgeDtos.TradeStorageEntry e = ts.get(fillType);
        double amount = e == null || e.amount() == null ? 0 : e.amount();
        return (long) Math.floor(amount) - reserved(sg, CaseKind.GOODS_REQUEST, fillType);
    }

    // ------------------------------------------------------------------------------------------ monthly messages

    /** Trade messages the neighbours sent on their own in the current game month (cap max-messages-per-month). */
    long messagesThisMonth(Savegame sg) {
        long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, KINDS).stream()
                .filter(c -> NEIGHBOR_INITIATIVE.equals(c.getTitle()))
                .filter(c -> gameTime.monthIndex(sg, c.getGameTime()) == month).count();
    }

    @EventListener
    @Order(81)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (f == null || f.tradeStorage() == null) {
            return; // the mod does not report the own silos (older mod): no trade
        }
        if (random.chance(cfg().getOfferProbabilityPerMonth()) && messagesThisMonth(sg) < cfg().getMaxMessagesPerMonth()) {
            spawnOffer(sg, f);
        }
        if (random.chance(cfg().getRequestProbabilityPerMonth()) && messagesThisMonth(sg) < cfg().getMaxMessagesPerMonth()) {
            spawnRequest(sg, f);
        }
    }

    private record Candidate(Character neighbor, String fillType, long max) {
    }

    /** R3-H3: a neighbour offers goods of his stock for which the player has room in his silos. */
    public java.util.Optional<ServiceCase> spawnOffer(Savegame sg, FarmFacts f) {
        Map<String, BridgeDtos.TradeStorageEntry> ts = neighbors.tradeStorage(f);
        List<Candidate> list = new ArrayList<>();
        for (Character n : neighbors.neighbors(sg)) {
            for (NeighborStock s : neighbors.stock(n)) {
                long max = maxAmount(Math.min(s.getAmount() * cfg().getMaxShare(), freeCapacity(sg, ts, s.getFillType())));
                if (max > 0 && neighbors.sellUnitPrice(f, n, s.getFillType()).isPresent()) {
                    list.add(new Candidate(n, s.getFillType(), max));
                }
            }
        }
        if (list.isEmpty()) {
            return java.util.Optional.empty();
        }
        Candidate c = random.pick(list);
        return java.util.Optional.of(offer(sg, f, c.neighbor(), c.fillType(), randomAmount(c.max()), false));
    }

    /** R3-H4: a neighbour asks for goods of his needs that the player has in his silos. */
    public java.util.Optional<ServiceCase> spawnRequest(Savegame sg, FarmFacts f) {
        Map<String, BridgeDtos.TradeStorageEntry> ts = neighbors.tradeStorage(f);
        List<Candidate> list = new ArrayList<>();
        for (Character n : neighbors.neighbors(sg)) {
            for (String fillType : neighbors.needs(n)) {
                long max = maxAmount(playerStock(sg, ts, fillType) * cfg().getMaxShare());
                if (max > 0 && neighbors.buyUnitPrice(f, n, fillType).isPresent()) {
                    list.add(new Candidate(n, fillType, max));
                }
            }
        }
        if (list.isEmpty()) {
            return java.util.Optional.empty();
        }
        Candidate c = random.pick(list);
        return java.util.Optional.of(request(sg, f, c.neighbor(), c.fillType(), randomAmount(c.max())));
    }

    // ------------------------------------------------------------------------------------------ offers and requests

    /** H3 on the page "Handel": the player asks a neighbour for goods of his stock; the neighbour answers at once. */
    @Transactional
    public ServiceCase requestGoods(Savegame sg, Long neighborId, String fillType, long liters) {
        Character n = characters.findById(neighborId).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .filter(NeighborService::isNeighbor).orElseThrow(() -> new NotFoundException("neighbour " + neighborId));
        FarmFacts f = facts(sg);
        Map<String, BridgeDtos.TradeStorageEntry> ts = neighbors.tradeStorage(f);
        if (liters <= 0) {
            throw new BusinessRuleException("TRADE_AMOUNT", "Bitte eine Menge über 0 Liter angeben.");
        }
        if (neighbors.stockOf(n, fillType) < liters) {
            throw new BusinessRuleException("TRADE_NO_STOCK", n.getName() + " hat nicht so viel "
                    + labels.label(fillType) + " auf Vorrat.");
        }
        if (freeCapacity(sg, ts, fillType) < liters) {
            throw new BusinessRuleException("TRADE_NO_CAPACITY", "In deinen Silos ist nicht genug Platz für "
                    + liters + " Liter " + labels.label(fillType) + ".");
        }
        OptionalDouble unit = neighbors.sellUnitPrice(f, n, fillType);
        if (unit.isEmpty()) {
            throw new BusinessRuleException("TRADE_NO_PRICE", "Für " + labels.label(fillType) + " gibt es keinen Preis.");
        }
        long price = neighbors.total(unit.getAsDouble(), liters);
        if (liquidity.available(sg) < price) {
            throw new BusinessRuleException("TRADE_FUNDS", "Dafür reicht dein Kontostand nicht (" + price + " €).");
        }
        return offer(sg, f, n, fillType, liters, true);
    }

    ServiceCase offer(Savegame sg, FarmFacts f, Character n, String fillType, long liters, boolean playerAsked) {
        double unit = neighbors.sellUnitPrice(f, n, fillType).orElseThrow();
        ServiceCase sc = newCase(sg, CaseKind.GOODS_OFFER, n, fillType, liters, unit, "BUY",
                playerAsked ? PLAYER_REQUEST : NEIGHBOR_INITIATIVE);
        narration.request(sg, NarrationEventType.GOODS_OFFER).from(n)
                .facts(facts(sc).put("playerAsked", playerAsked)
                        .put("intro", playerAsked ? "du hattest nach Ware gefragt." : "bei mir ist noch Ware übrig.")
                        .build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).formLink(link(sc)).submit();
        return sc;
    }

    ServiceCase request(Savegame sg, FarmFacts f, Character n, String fillType, long liters) {
        double unit = neighbors.buyUnitPrice(f, n, fillType).orElseThrow();
        ServiceCase sc = newCase(sg, CaseKind.GOODS_REQUEST, n, fillType, liters, unit, "SELL", NEIGHBOR_INITIATIVE);
        narration.request(sg, NarrationEventType.GOODS_REQUEST).from(n).facts(facts(sc).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).formLink(link(sc)).submit();
        return sc;
    }

    private ServiceCase newCase(Savegame sg, CaseKind kind, Character n, String fillType, long liters, double unit,
                                String direction, String origin) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(kind);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(n);
        sc.setReference(fillType);
        sc.setQuantity((int) liters);
        sc.setCostAmount(Math.round(unit));
        sc.setOfferAmount(neighbors.total(unit, liters));
        sc.setDirection(direction);
        sc.setTitle(origin);
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getAnswerDays()));
        sc.setCreatedAt(java.time.Instant.now());
        return cases.save(sc);
    }

    private NarrationFacts.Builder facts(ServiceCase sc) {
        return NarrationFacts.builder().put("fillType", sc.getReference()).put("amount", sc.getQuantity())
                .put("price", sc.getOfferAmount()).put("unitPrice", sc.getCostAmount())
                .put("answerDays", Math.round(cfg().getAnswerDays()));
    }

    static String link(ServiceCase sc) {
        return "/handel?case=" + sc.getId();
    }

    private FarmFacts facts(Savegame sg) {
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (f == null || f.tradeStorage() == null) {
            throw new BusinessRuleException("TRADE_NO_SILOS", "Der Mod meldet deine Silos nicht – bitte den Mod "
                    + "FS25_RPSim aktualisieren.");
        }
        return f;
    }

    // ------------------------------------------------------------------------------------------ decisions

    ServiceCase open(Savegame sg, Long id) {
        ServiceCase sc = cases.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (!KINDS.contains(sc.getKind()) || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieses Angebot ist nicht mehr offen.");
        }
        return sc;
    }

    /** The player agrees: checks stock, room and money again and sends the batch (price as agreed). */
    @Transactional
    public ServiceCase accept(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        FarmFacts f = facts(sg);
        Map<String, BridgeDtos.TradeStorageEntry> ts = neighbors.tradeStorage(f);
        String fillType = sc.getReference();
        long liters = sc.getQuantity();
        String what = liters + " l " + labels.label(fillType);
        Related related = new Related(RELATED, sc.getId());
        if (sc.getKind() == CaseKind.GOODS_OFFER) {
            if (neighbors.stockOf(sc.getCharacter(), fillType) < liters) {
                throw new BusinessRuleException("TRADE_NO_STOCK", sc.getCharacter().getName() + " hat inzwischen nicht "
                        + "mehr so viel " + labels.label(fillType) + ".");
            }
            if (freeCapacity(sg, ts, fillType) < liters) {
                throw new BusinessRuleException("TRADE_NO_CAPACITY", "In deinen Silos ist nicht genug Platz für " + what + ".");
            }
            if (liquidity.available(sg) < sc.getOfferAmount()) {
                throw new BusinessRuleException("TRADE_FUNDS", "Dafür reicht dein Kontostand nicht ("
                        + sc.getOfferAmount() + " €).");
            }
            neighbors.addStock(sg, sc.getCharacter(), fillType, -liters); // reserved; back on failure
            outbox.storageDeal(sg, true, fillType, liters, sc.getOfferAmount(), MoneyReason.GOODS_PURCHASE,
                    what + " von " + sc.getCharacter().getName(), related);
        } else {
            if (playerStock(sg, ts, fillType) < liters) {
                throw new BusinessRuleException("TRADE_NO_STOCK", "In deinen Silos liegt nicht genug " + labels.label(fillType)
                        + " (" + liters + " l).");
            }
            outbox.storageDeal(sg, false, fillType, liters, sc.getOfferAmount(), MoneyReason.GOODS_SALE,
                    what + " an " + sc.getCharacter().getName(), related);
        }
        sc.setStatus(CaseStatus.IN_PROGRESS);
        return sc;
    }

    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        close(sc, CaseStatus.DECLINED, "PLAYER");
        trust.recordEvent(sc.getCharacter(), cfg().getDeclineTrustDelta(), TrustReason.NEIGHBOR_TRADE_DECLINED,
                labels.label(sc.getReference()));
        return sc;
    }

    /** Daily: an offer or request left unanswered until the deadline costs more trust than a refusal. */
    @EventListener
    @Order(81)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (KINDS.contains(sc.getKind()) && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
                close(sc, CaseStatus.EXPIRED, "NO_ANSWER");
                trust.recordEvent(sc.getCharacter(), cfg().getIgnoreTrustDelta(), TrustReason.NEIGHBOR_TRADE_IGNORED,
                        labels.label(sc.getReference()));
            }
        }
    }

    private void close(ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sc.getSavegame().getCurrentGameTime());
    }

    // ------------------------------------------------------------------------------------------ bridge acks

    /** The mod moved the goods (the money part of the batch was booked in the same cycle). */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        boolean transfer = instructions.findByInstructionId(e.instructionId())
                .map(o -> o.getType() == InstructionType.STORAGE_TRANSFER).orElse(false);
        ServiceCase sc = cases.findById(e.relatedId()).orElse(null);
        if (!transfer || sc == null || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return;
        }
        Savegame sg = sc.getSavegame();
        close(sc, CaseStatus.SETTLED, "DONE");
        Character n = sc.getCharacter();
        trust.recordEvent(n, cfg().getTradeTrustDelta(), TrustReason.NEIGHBOR_TRADE, labels.label(sc.getReference()));
        boolean bought = sc.getKind() == CaseKind.GOODS_OFFER;
        diary.addAuto(sg, "TRADE", "Handel mit " + n.getName(), sc.getQuantity() + " l " + labels.label(sc.getReference())
                + (bought ? " von " + n.getName() + " gekauft" : " an " + n.getName() + " verkauft") + " ("
                + sc.getOfferAmount() + " €).", RELATED, sc.getId());
        if (!bought) {
            reputation(sg, n);
        }
        narration.request(sg, NarrationEventType.GOODS_TRADE_DONE).from(n).facts(facts(sc).put("bought", bought).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).submit();
    }

    /** R3-H4: helping a neighbour out raises the village reputation, at most reputation-max-per-year times a year. */
    private void reputation(Savegame sg, Character n) {
        long now = sg.getCurrentGameTime();
        long month = gameTime.monthIndex(sg, now);
        long yearStart = gameTime.monthStart(sg, month - (gameTime.periodOfYear(sg, now) - 1));
        if (publicEvents.countBySavegameAndTypeAndGameTimeGreaterThanEqual(sg, PublicActionType.NEIGHBOR_HELP, yearStart)
                < cfg().getReputationMaxPerYear()) {
            publicActions.record(sg, PublicActionType.NEIGHBOR_HELP, cfg().getReputationDelta(),
                    "Ware an " + n.getName() + " abgegeben");
        }
    }

    /**
     * FailedInstructionService: the mod did not move the goods. A purchase gives the reserved stock back; a sale with
     * too little in the silo disappoints the neighbour (INSUFFICIENT_STOCK). Returns true when the case was open.
     */
    @Transactional
    public boolean onInstructionFailed(Long caseId, String message) {
        ServiceCase sc = cases.findById(caseId).orElse(null);
        if (sc == null || sc.getStatus() != CaseStatus.IN_PROGRESS || !KINDS.contains(sc.getKind())) {
            return false;
        }
        Savegame sg = sc.getSavegame();
        if (sc.getKind() == CaseKind.GOODS_OFFER) {
            neighbors.addStock(sg, sc.getCharacter(), sc.getReference(), sc.getQuantity());
            close(sc, CaseStatus.EXPIRED, "FAILED");
        } else if (message != null && message.contains("INSUFFICIENT_STOCK")) {
            close(sc, CaseStatus.EXPIRED, "NO_STOCK");
            trust.recordEvent(sc.getCharacter(), cfg().getStockMissingTrustDelta(), TrustReason.NEIGHBOR_DISAPPOINTED,
                    labels.label(sc.getReference()));
            narration.request(sg, NarrationEventType.GOODS_REQUEST_FAILED).from(sc.getCharacter()).facts(facts(sc).build())
                    .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).submit();
        } else {
            close(sc, CaseStatus.EXPIRED, "FAILED");
        }
        return true;
    }

    /** Open and running trade cases (page "Handel"). */
    public List<ServiceCase> cases(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, KINDS);
    }

    /** Price per price unit the neighbour sells at (page "Handel"), empty without a price. */
    public OptionalDouble sellUnitPrice(Savegame sg, Character n, String fillType) {
        return neighbors.sellUnitPrice(neighbors.latest(sg).orElse(null), n, fillType);
    }
}
