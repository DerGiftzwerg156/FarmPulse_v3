package de.farmpulse.rpsim.neighbor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
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
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
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
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-M3: farm shop - villagers order small amounts from the own silos (owner decisions in QUESTIONS.md).
 * <ul>
 *   <li>Monthly: up to max-orders-per-month orders, each with probability x the refusal factor of the savegame. A
 *   villager orders a configured fill type that lies in the own silos ({@code tradeStorage}), 200-2,000 l, at most 20 %
 *   of the stock, at the farm-shop price = best market price x markup.</li>
 *   <li>"Liefern" executes like R3-H4: {@code STORAGE_TRANSFER OUT} + {@code MONEY_TRANSACTION GOODS_SALE}. A delivered
 *   order raises the village reputation (FARM_SHOP, capped per FS25 year) and the factor one step.</li>
 *   <li>Declining or ignoring until the deadline lowers the factor (x refusal-factor, at least min-factor); no trust
 *   change for the villager.</li>
 * </ul>
 */
@Service
public class FarmShopService {

    public static final String RELATED = "FARM_SHOP";

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final NeighborService neighbors;
    private final NeighborTradeService trade;
    private final OutboxService outbox;
    private final OutboxInstructionRepository instructions;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final PublicActionService publicActions;
    private final PublicActionEventRepository publicEvents;
    private final FallbackTemplates labels;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public FarmShopService(ServiceCaseRepository cases, SavegameRepository savegames, CharacterRepository characters,
                           NeighborService neighbors, NeighborTradeService trade,
                           OutboxService outbox, OutboxInstructionRepository instructions,
                           NarrationRequestService narration, DiaryService diary, PublicActionService publicActions,
                           PublicActionEventRepository publicEvents, FallbackTemplates labels, RandomSource random,
                           RpsimProperties props, GameTime gameTime) {
        this.cases = cases;
        this.savegames = savegames;
        this.characters = characters;
        this.neighbors = neighbors;
        this.trade = trade;
        this.outbox = outbox;
        this.instructions = instructions;
        this.narration = narration;
        this.diary = diary;
        this.publicActions = publicActions;
        this.publicEvents = publicEvents;
        this.labels = labels;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.FarmShop cfg() {
        return props.getFormulas().getFarmShop();
    }

    /** Best market price of a fill type (EUR per 1000 l), empty without a price on the map. */
    static Optional<Double> bestPrice(FarmFacts f, String fillType) {
        if (f == null || f.prices() == null) {
            return Optional.empty();
        }
        return f.prices().stream().filter(p -> fillType.equals(p.fillType()) && p.currentPrice() != null && p.currentPrice() > 0)
                .map(BridgeDtos.Price::currentPrice).max(Double::compare);
    }

    /** Largest multiple of amount-step up to {@code max} and amount-max; 0 below amount-min. */
    long maxAmount(double max) {
        long step = cfg().getAmountStep();
        long capped = (long) Math.floor(Math.min(max, cfg().getAmountMax()) / step) * step;
        return capped < cfg().getAmountMin() ? 0 : capped;
    }

    // ------------------------------------------------------------------------------------------ monthly orders

    @EventListener
    @Order(83)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled()) {
            return;
        }
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (f == null || f.tradeStorage() == null) {
            return; // older mod: the own silos are unknown
        }
        double p = cfg().getProbability() * sg.getFarmShopFactor();
        for (int i = 0; i < cfg().getMaxOrdersPerMonth(); i++) {
            if (random.chance(p)) {
                spawnOrder(sg, f);
            }
        }
    }

    private record Candidate(String fillType, long max, double unit) {
    }

    /** One order of a villager from the goods in the own silos (empty when nothing fits). */
    public Optional<ServiceCase> spawnOrder(Savegame sg, FarmFacts f) {
        List<Character> villagers = characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE).stream()
                .filter(c -> c.getRole() == CharacterRole.VILLAGER).toList();
        if (villagers.isEmpty()) {
            return Optional.empty();
        }
        Map<String, BridgeDtos.TradeStorageEntry> ts = neighbors.tradeStorage(f);
        List<Candidate> list = new ArrayList<>();
        for (String fillType : cfg().getFillTypes()) {
            long max = maxAmount(trade.playerStock(sg, ts, fillType) * cfg().getMaxShare());
            Optional<Double> best = bestPrice(f, fillType);
            if (max > 0 && best.isPresent()) {
                list.add(new Candidate(fillType, max, best.get() * cfg().getMarkup()));
            }
        }
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Candidate c = random.pick(list);
        long step = cfg().getAmountStep();
        int steps = (int) ((c.max() - cfg().getAmountMin()) / step);
        long liters = cfg().getAmountMin() + step * random.intBetween(0, Math.max(0, steps));
        Character villager = random.pick(villagers);
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.FARM_SHOP_ORDER);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(villager);
        sc.setReference(c.fillType());
        sc.setQuantity((int) liters);
        sc.setCostAmount(Math.round(c.unit()));
        sc.setOfferAmount(neighbors.total(c.unit(), liters));
        sc.setDirection("SELL");
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getAnswerDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        narration.request(sg, NarrationEventType.FARM_SHOP_ORDER).from(villager)
                .facts(NarrationFacts.builder().put("fillType", c.fillType()).put("amount", liters)
                        .put("price", sc.getOfferAmount()).put("unitPrice", sc.getCostAmount())
                        .put("answerDays", Math.round(cfg().getAnswerDays())).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).formLink("/handel?case=" + sc.getId())
                .submit();
        return Optional.of(sc);
    }

    // ------------------------------------------------------------------------------------------ decisions

    private ServiceCase open(Savegame sg, Long id) {
        ServiceCase sc = cases.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != CaseKind.FARM_SHOP_ORDER || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Diese Bestellung ist nicht mehr offen.");
        }
        return sc;
    }

    /** "Liefern": OUT + GOODS_SALE as one batch, checked against the stock again. */
    @Transactional
    public ServiceCase accept(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (f == null || f.tradeStorage() == null) {
            throw new BusinessRuleException("TRADE_NO_SILOS", "Der Mod meldet deine Silos nicht – bitte den Mod "
                    + "FS25_RPSim aktualisieren.");
        }
        long liters = sc.getQuantity();
        if (trade.playerStock(sg, neighbors.tradeStorage(f), sc.getReference()) < liters) {
            throw new BusinessRuleException("TRADE_NO_STOCK", "In deinen Silos liegt nicht genug "
                    + labels.label(sc.getReference()) + " (" + liters + " l).");
        }
        outbox.storageDeal(sg, false, sc.getReference(), liters, sc.getOfferAmount(), MoneyReason.GOODS_SALE,
                "Hofladen: " + liters + " l " + labels.label(sc.getReference()) + " an " + sc.getCharacter().getName(),
                new Related(RELATED, sc.getId()));
        sc.setStatus(CaseStatus.IN_PROGRESS);
        return sc;
    }

    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        close(sc, CaseStatus.DECLINED, "PLAYER");
        lowerFactor(sg);
        return sc;
    }

    /** Daily: an order left unanswered counts like a refusal. */
    @EventListener
    @Order(82)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() == CaseKind.FARM_SHOP_ORDER && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
                close(sc, CaseStatus.EXPIRED, "NO_ANSWER");
                lowerFactor(sg);
            }
        }
    }

    void lowerFactor(Savegame sg) {
        sg.setFarmShopFactor(Math.max(cfg().getMinFactor(), sg.getFarmShopFactor() * cfg().getRefusalFactor()));
    }

    void raiseFactor(Savegame sg) {
        sg.setFarmShopFactor(Math.min(1.0, sg.getFarmShopFactor() / cfg().getRefusalFactor()));
    }

    private void close(ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sc.getSavegame().getCurrentGameTime());
    }

    // ------------------------------------------------------------------------------------------ bridge acks

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
        if (!transfer || sc == null || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return;
        }
        Savegame sg = sc.getSavegame();
        close(sc, CaseStatus.SETTLED, "DONE");
        raiseFactor(sg);
        diary.addAuto(sg, "TRADE", "Hofladen", sc.getQuantity() + " l " + labels.label(sc.getReference()) + " an "
                + sc.getCharacter().getName() + " verkauft (" + sc.getOfferAmount() + " €).", RELATED, sc.getId());
        long now = sg.getCurrentGameTime();
        long month = gameTime.monthIndex(sg, now);
        long yearStart = gameTime.monthStart(sg, month - (gameTime.periodOfYear(sg, now) - 1));
        if (publicEvents.countBySavegameAndTypeAndGameTimeGreaterThanEqual(sg, PublicActionType.FARM_SHOP, yearStart)
                < cfg().getReputationMaxPerYear()) {
            publicActions.record(sg, PublicActionType.FARM_SHOP, cfg().getReputationDelta(),
                    "Hofladen: Ware an " + sc.getCharacter().getName());
        }
    }

    /** FailedInstructionService: the mod did not take the goods (too little in the silo). */
    @Transactional
    public boolean onInstructionFailed(Long caseId, String message) {
        ServiceCase sc = cases.findById(caseId).orElse(null);
        if (sc == null || sc.getKind() != CaseKind.FARM_SHOP_ORDER || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return false;
        }
        close(sc, CaseStatus.EXPIRED, message != null && message.contains("INSUFFICIENT_STOCK") ? "NO_STOCK" : "FAILED");
        return true;
    }

    public List<ServiceCase> orders(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.FARM_SHOP_ORDER));
    }
}
