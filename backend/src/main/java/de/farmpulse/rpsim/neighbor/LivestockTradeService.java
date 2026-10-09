package de.farmpulse.rpsim.neighbor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NeighborAnimalStock;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.NeighborAnimalStockRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-A3: animals bought from and sold to the neighbours (owner decisions in QUESTIONS.md). The animals
 * move for real ({@code ANIMAL_TRANSFER} + {@code LIVESTOCK_PURCHASE} / {@code LIVESTOCK_SALE} as one batch); the
 * neighbours' stock is backend fiction from their role (rolled when first needed), every price comes from the backend.
 * <ul>
 *   <li>Offer (ANIMAL_OFFER): a neighbour sells animals of a subtype an own stable accepts (supportedSubTypes) and has
 *   room for (freeSlots) - on his own at a month start or asked by the player on the page "Handel".</li>
 *   <li>Request (ANIMAL_REQUEST): a neighbour buys animals of a subtype the player has (subTypes) - on his own or offered
 *   by the player.</li>
 * </ul>
 * Price per animal = game value per animal of the player's stables of that type ({@code estimatedValue / count}) x share,
 * trust as in the goods trade. Answer time, trust changes and the monthly chances follow the goods trade (R3-H); at most
 * max-messages-per-month livestock messages a month. The livestock trader ({@code LIVESTOCK_TRADER}) stays for deals
 * outside the village. The trade lock of an animal disease follows with R31-B4.
 */
@Service
public class LivestockTradeService {

    public static final String RELATED = "LIVESTOCK_TRADE";
    public static final String PLAYER_REQUEST = "PLAYER_REQUEST";
    public static final String NEIGHBOR_INITIATIVE = "NEIGHBOR";
    static final Set<CaseKind> KINDS = EnumSet.of(CaseKind.ANIMAL_OFFER, CaseKind.ANIMAL_REQUEST);

    /** One own husbandry with the data of the export (type from assets.animals, subtypes from husbandries). */
    public record Stable(String husbandryUniqueId, String type, int count, double value, Integer freeSlots,
                         List<BridgeDtos.SubTypeCount> subTypes, List<String> supportedSubTypes) {
    }

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final NeighborAnimalStockRepository stocks;
    private final NeighborService neighbors;
    private final OutboxService outbox;
    private final OutboxInstructionRepository instructions;
    private final LiquidityService liquidity;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;
    private final de.farmpulse.rpsim.character.CharacterLookup lookup;
    /** Roadmap V3.1 R31-B4: restricted zones of an animal disease. */
    private final de.farmpulse.rpsim.authority.DiseaseZones zones;

    public LivestockTradeService(ServiceCaseRepository cases, SavegameRepository savegames, CharacterRepository characters,
                                 NeighborAnimalStockRepository stocks, NeighborService neighbors, OutboxService outbox,
                                 OutboxInstructionRepository instructions, LiquidityService liquidity,
                                 TrustScoreService trust, NarrationRequestService narration, DiaryService diary,
                                 RandomSource random, RpsimProperties props, GameTime gameTime,
                                 de.farmpulse.rpsim.character.CharacterLookup lookup,
                                 de.farmpulse.rpsim.authority.DiseaseZones zones) {
        this.lookup = lookup;
        this.zones = zones;
        this.cases = cases;
        this.savegames = savegames;
        this.characters = characters;
        this.stocks = stocks;
        this.neighbors = neighbors;
        this.outbox = outbox;
        this.instructions = instructions;
        this.liquidity = liquidity;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.LivestockTrade cfg() {
        return props.getFormulas().getLivestockTrade();
    }

    /** Answer time and trust changes as in the goods trade. */
    private RpsimProperties.NeighborTrade trade() {
        return props.getFormulas().getNeighborTrade();
    }

    // ------------------------------------------------------------------------------------------ stables and stock

    /** The own stables; empty when the mod does not export the subtypes (older mod). */
    public List<Stable> stables(FarmFacts f) {
        List<Stable> list = new ArrayList<>();
        if (f == null || f.husbandries() == null || f.assets() == null || f.assets().animals() == null) {
            return list;
        }
        for (BridgeDtos.Husbandry h : f.husbandries()) {
            if (h == null || h.subTypes() == null || h.supportedSubTypes() == null) {
                continue;
            }
            f.assets().animals().stream().filter(a -> a != null && h.husbandryUniqueId().equals(a.husbandryUniqueId()))
                    .findFirst().ifPresent(a -> list.add(new Stable(h.husbandryUniqueId(), a.type(),
                            a.count() == null ? 0 : a.count(), a.estimatedValue() == null ? 0 : a.estimatedValue(),
                            h.freeSlots(), h.subTypes(), h.supportedSubTypes())));
        }
        return list;
    }

    /** Animal types the neighbour keeps (from his role). */
    public List<String> animalTypes(Character n) {
        return cfg().getRoleAnimals().getOrDefault(neighbors.ensureRole(n), List.of());
    }

    /** The neighbour's animals of a type he keeps; rolled stock-min..stock-max when first needed. */
    @Transactional
    public int stockOf(Savegame sg, Character n, String type) {
        if (!animalTypes(n).contains(type)) {
            return 0;
        }
        return stocks.findByCharacterAndAnimalType(n, type).orElseGet(() -> {
            NeighborAnimalStock s = new NeighborAnimalStock();
            s.setSavegame(sg);
            s.setCharacter(n);
            s.setAnimalType(type);
            s.setCount(random.intBetween(cfg().getStockMin(), cfg().getStockMax()));
            s.setUpdatedGameTime(sg.getCurrentGameTime());
            return stocks.save(s);
        }).getCount();
    }

    @Transactional
    public void addStock(Savegame sg, Character n, String type, int delta) {
        stockOf(sg, n, type);
        stocks.findByCharacterAndAnimalType(n, type).ifPresent(s -> {
            s.setCount(Math.max(0, s.getCount() + delta));
            s.setUpdatedGameTime(sg.getCurrentGameTime());
        });
    }

    // ------------------------------------------------------------------------------------------ prices

    /** Game value per animal of the player's stables of that type; empty without animals of the type. */
    public OptionalDouble valuePerAnimal(FarmFacts f, String type) {
        if (f == null || f.assets() == null || f.assets().animals() == null) {
            return OptionalDouble.empty();
        }
        double value = 0;
        int count = 0;
        for (BridgeDtos.Animal a : f.assets().animals()) {
            if (a != null && type.equals(a.type()) && a.count() != null && a.count() > 0 && a.estimatedValue() != null) {
                value += a.estimatedValue();
                count += a.count();
            }
        }
        return count == 0 ? OptionalDouble.empty() : OptionalDouble.of(value / count);
    }

    /**
     * Price per animal when the neighbour sells (good trust = cheaper); Roadmap V3.1 R31-B4: x the price factor after
     * an animal disease.
     */
    public OptionalDouble sellUnitPrice(FarmFacts f, Character n, String type) {
        OptionalDouble v = valuePerAnimal(f, type);
        double adj = neighbors.trustAdjustment(trust.getCurrentTrust(n));
        return v.isEmpty() ? v : OptionalDouble.of(v.getAsDouble() * cfg().getNeighborSellShare() * (1 - adj)
                * zones.priceFactor(n.getSavegame(), type));
    }

    /** Price per animal when the neighbour buys (good trust = he pays more); R31-B4 price factor as above. */
    public OptionalDouble buyUnitPrice(FarmFacts f, Character n, String type) {
        OptionalDouble v = valuePerAnimal(f, type);
        double adj = neighbors.trustAdjustment(trust.getCurrentTrust(n));
        return v.isEmpty() ? v : OptionalDouble.of(v.getAsDouble() * cfg().getNeighborBuyShare() * (1 + adj)
                * zones.priceFactor(n.getSavegame(), type));
    }

    // ------------------------------------------------------------------------------------------ room and stock of the player

    /** Animals already on the way into (accepted purchases) or out of (accepted sales) a stable. */
    int reserved(Savegame sg, CaseKind kind, String husbandryUniqueId, String subType) {
        return cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS).stream()
                .filter(c -> c.getKind() == kind && husbandryUniqueId.equals(c.getExternalId())
                        && (subType == null || subType.equals(c.getReference())))
                .mapToInt(c -> c.getQuantity() == null ? 0 : c.getQuantity()).sum();
    }

    /** Free places of a stable minus the animals already bought and on their way in. */
    public int freePlaces(Savegame sg, Stable s) {
        return (s.freeSlots() == null ? 0 : s.freeSlots()) - reserved(sg, CaseKind.ANIMAL_OFFER, s.husbandryUniqueId(), null);
    }

    /** Animals of a subtype in a stable minus the animals already sold and on their way out. */
    public int available(Savegame sg, Stable s, String subType) {
        int count = s.subTypes().stream().filter(x -> subType.equals(x.name())).mapToInt(x -> x.count() == null ? 0 : x.count())
                .sum();
        return count - reserved(sg, CaseKind.ANIMAL_REQUEST, s.husbandryUniqueId(), subType);
    }

    // ------------------------------------------------------------------------------------------ monthly messages

    long messagesThisMonth(Savegame sg) {
        long month = gameTime.monthIndex(sg, sg.getCurrentGameTime());
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, KINDS).stream()
                .filter(c -> c.getDirection() != null && c.getDirection().startsWith(NEIGHBOR_INITIATIVE + ":"))
                .filter(c -> gameTime.monthIndex(sg, c.getGameTime()) == month).count();
    }

    @EventListener
    @Order(84)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        if (!cfg().isEnabled()) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (stables(f).isEmpty()) {
            return; // no stable or an older mod without subtypes: no livestock trade
        }
        if (random.chance(cfg().getOfferProbabilityPerMonth()) && messagesThisMonth(sg) < cfg().getMaxMessagesPerMonth()) {
            spawnOffer(sg, f);
        }
        if (random.chance(cfg().getRequestProbabilityPerMonth()) && messagesThisMonth(sg) < cfg().getMaxMessagesPerMonth()) {
            spawnRequest(sg, f);
        }
    }

    private record Candidate(Character neighbor, Stable stable, String subType, int max) {
    }

    /** A neighbour offers animals of a type he keeps for a stable with room. */
    public Optional<ServiceCase> spawnOffer(Savegame sg, FarmFacts f) {
        List<Candidate> list = new ArrayList<>();
        for (Character n : neighbors.neighbors(sg)) {
            for (Stable s : stables(f)) {
                if (!animalTypes(n).contains(s.type()) || s.supportedSubTypes().isEmpty()
                        || sellUnitPrice(f, n, s.type()).isEmpty() || zones.blocked(sg, s.type())) {
                    continue;
                }
                int max = Math.min(cfg().getCountMax(), Math.min(stockOf(sg, n, s.type()), freePlaces(sg, s)));
                if (max >= cfg().getCountMin()) {
                    list.add(new Candidate(n, s, random.pick(s.supportedSubTypes()), max));
                }
            }
        }
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Candidate c = random.pick(list);
        return Optional.of(offer(sg, f, c.neighbor(), c.stable(), c.subType(),
                random.intBetween(cfg().getCountMin(), c.max()), false));
    }

    /** A neighbour asks for animals of a type he keeps that the player has. */
    public Optional<ServiceCase> spawnRequest(Savegame sg, FarmFacts f) {
        List<Candidate> list = new ArrayList<>();
        for (Character n : neighbors.neighbors(sg)) {
            for (Stable s : stables(f)) {
                if (!animalTypes(n).contains(s.type()) || buyUnitPrice(f, n, s.type()).isEmpty()
                        || zones.blocked(sg, s.type())) {
                    continue;
                }
                for (BridgeDtos.SubTypeCount st : s.subTypes()) {
                    int max = Math.min(cfg().getCountMax(), available(sg, s, st.name()));
                    if (max >= cfg().getCountMin()) {
                        list.add(new Candidate(n, s, st.name(), max));
                    }
                }
            }
        }
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Candidate c = random.pick(list);
        return Optional.of(request(sg, f, c.neighbor(), c.stable(), c.subType(),
                random.intBetween(cfg().getCountMin(), c.max()), false));
    }

    // ------------------------------------------------------------------------------------------ player on the page "Handel"

    /** "Tiere anfragen": the player asks a neighbour for animals; he answers at once with his price. */
    @Transactional
    public ServiceCase requestAnimals(Savegame sg, Long neighborId, String husbandryUniqueId, String subType, int count) {
        Character n = neighbor(sg, neighborId);
        FarmFacts f = facts(sg);
        Stable s = stable(f, husbandryUniqueId);
        checkCount(count);
        zones.requireOpen(sg, s.type()); // R31-B4
        if (!animalTypes(n).contains(s.type())) {
            throw new BusinessRuleException("ANIMAL_TYPE", n.getName() + " hält keine Tiere dieser Art.");
        }
        if (!s.supportedSubTypes().contains(subType)) {
            throw new BusinessRuleException("ANIMAL_SUBTYPE", "Diese Rasse passt nicht in den Stall.");
        }
        if (stockOf(sg, n, s.type()) < count) {
            throw new BusinessRuleException("ANIMAL_NO_STOCK", n.getName() + " hat nicht so viele Tiere.");
        }
        if (freePlaces(sg, s) < count) {
            throw new BusinessRuleException("ANIMAL_NO_SPACE", "Im Stall ist nicht genug Platz für " + count + " Tiere.");
        }
        OptionalDouble unit = sellUnitPrice(f, n, s.type());
        if (unit.isEmpty()) {
            throw new BusinessRuleException("ANIMAL_NO_PRICE", "Für diese Tierart ist kein Wert bekannt.");
        }
        if (liquidity.available(sg) < Math.round(unit.getAsDouble() * count)) {
            throw new BusinessRuleException("ANIMAL_FUNDS", "Dafür reicht dein Kontostand nicht.");
        }
        return offer(sg, f, n, s, subType, count, true);
    }

    /** "Tiere anbieten": the player offers animals of a stable; the neighbour answers at once with his price. */
    @Transactional
    public ServiceCase offerAnimals(Savegame sg, Long neighborId, String husbandryUniqueId, String subType, int count) {
        Character n = neighbor(sg, neighborId);
        FarmFacts f = facts(sg);
        Stable s = stable(f, husbandryUniqueId);
        checkCount(count);
        zones.requireOpen(sg, s.type()); // R31-B4
        if (!animalTypes(n).contains(s.type())) {
            throw new BusinessRuleException("ANIMAL_TYPE", n.getName() + " hält keine Tiere dieser Art.");
        }
        if (available(sg, s, subType) < count) {
            throw new BusinessRuleException("ANIMAL_NO_ANIMALS", "Im Stall stehen nicht so viele Tiere dieser Rasse.");
        }
        if (buyUnitPrice(f, n, s.type()).isEmpty()) {
            throw new BusinessRuleException("ANIMAL_NO_PRICE", "Für diese Tierart ist kein Wert bekannt.");
        }
        return request(sg, f, n, s, subType, count, true);
    }

    private void checkCount(int count) {
        if (count < cfg().getCountMin() || count > cfg().getCountMax()) {
            throw new BusinessRuleException("ANIMAL_COUNT", "Bitte " + cfg().getCountMin() + " bis " + cfg().getCountMax()
                    + " Tiere wählen.");
        }
    }

    ServiceCase offer(Savegame sg, FarmFacts f, Character n, Stable s, String subType, int count, boolean playerAsked) {
        double unit = sellUnitPrice(f, n, s.type()).orElseThrow();
        ServiceCase sc = newCase(sg, CaseKind.ANIMAL_OFFER, n, s, subType, count, unit, playerAsked);
        narration.request(sg, NarrationEventType.ANIMAL_OFFER).from(n).facts(facts(sc).put("playerAsked", playerAsked).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).formLink(link(sc)).submit();
        return sc;
    }

    ServiceCase request(Savegame sg, FarmFacts f, Character n, Stable s, String subType, int count, boolean playerAsked) {
        double unit = buyUnitPrice(f, n, s.type()).orElseThrow();
        ServiceCase sc = newCase(sg, CaseKind.ANIMAL_REQUEST, n, s, subType, count, unit, playerAsked);
        narration.request(sg, NarrationEventType.ANIMAL_REQUEST).from(n).facts(facts(sc).put("playerAsked", playerAsked).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).formLink(link(sc)).submit();
        return sc;
    }

    private ServiceCase newCase(Savegame sg, CaseKind kind, Character n, Stable s, String subType, int count, double unit,
                                boolean playerAsked) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(kind);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(n);
        sc.setReference(subType);
        sc.setTitle(s.type());
        sc.setExternalId(s.husbandryUniqueId());
        sc.setQuantity(count);
        sc.setCostAmount(Math.round(unit));
        sc.setOfferAmount(Math.round(unit * count));
        sc.setDirection((playerAsked ? PLAYER_REQUEST : NEIGHBOR_INITIATIVE) + ":" + (kind == CaseKind.ANIMAL_OFFER ? "BUY" : "SELL"));
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(trade().getAnswerDays()));
        sc.setCreatedAt(java.time.Instant.now());
        return cases.save(sc);
    }

    private NarrationFacts.Builder facts(ServiceCase sc) {
        return NarrationFacts.builder().put("animalType", sc.getTitle()).put("subType", sc.getReference())
                .put("count", sc.getQuantity()).put("unitPrice", sc.getCostAmount()).put("price", sc.getOfferAmount())
                .put("answerDays", Math.round(trade().getAnswerDays()));
    }

    static String link(ServiceCase sc) {
        return "/handel?case=" + sc.getId();
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

    /** The player agrees: room, animals and money are checked again, then the batch goes to the mod. */
    @Transactional
    public ServiceCase accept(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        FarmFacts f = facts(sg);
        Stable s = stable(f, sc.getExternalId());
        int count = sc.getQuantity();
        Character n = sc.getCharacter();
        zones.requireOpen(sg, sc.getTitle()); // R31-B4: restricted zone of an animal disease
        String what = count + " " + sc.getReference();
        Related related = new Related(RELATED, sc.getId());
        if (sc.getKind() == CaseKind.ANIMAL_OFFER) {
            if (stockOf(sg, n, sc.getTitle()) < count) {
                throw new BusinessRuleException("ANIMAL_NO_STOCK", n.getName() + " hat inzwischen nicht mehr so viele Tiere.");
            }
            if (freePlaces(sg, s) < count) {
                throw new BusinessRuleException("ANIMAL_NO_SPACE", "Im Stall ist nicht genug Platz für " + count + " Tiere.");
            }
            if (liquidity.available(sg) < sc.getOfferAmount()) {
                throw new BusinessRuleException("ANIMAL_FUNDS", "Dafür reicht dein Kontostand nicht (" + sc.getOfferAmount()
                        + " €).");
            }
            addStock(sg, n, sc.getTitle(), -count); // reserved; back on failure
            outbox.animalDeal(sg, true, s.husbandryUniqueId(), sc.getReference(), count,
                    cfg().getAgeMonths().get(sc.getTitle()), sc.getOfferAmount(), what + " von " + n.getName(), related);
        } else {
            if (available(sg, s, sc.getReference()) < count) {
                throw new BusinessRuleException("ANIMAL_NO_ANIMALS", "Im Stall stehen nicht mehr so viele Tiere dieser Rasse.");
            }
            outbox.animalDeal(sg, false, s.husbandryUniqueId(), sc.getReference(), count, null, sc.getOfferAmount(),
                    what + " an " + n.getName(), related);
        }
        sc.setStatus(CaseStatus.IN_PROGRESS);
        return sc;
    }

    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        close(sc, CaseStatus.DECLINED, "PLAYER");
        trust.recordEvent(sc.getCharacter(), trade().getDeclineTrustDelta(), TrustReason.NEIGHBOR_TRADE_DECLINED,
                sc.getReference());
        return sc;
    }

    /** Daily: an offer or request left unanswered until the deadline costs more trust than a refusal. */
    @EventListener
    @Order(84)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (KINDS.contains(sc.getKind()) && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
                close(sc, CaseStatus.EXPIRED, "NO_ANSWER");
                trust.recordEvent(sc.getCharacter(), trade().getIgnoreTrustDelta(), TrustReason.NEIGHBOR_TRADE_IGNORED,
                        sc.getReference());
            }
        }
    }

    private void close(ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sc.getSavegame().getCurrentGameTime());
    }

    // ------------------------------------------------------------------------------------------ bridge acks

    /** The mod moved the animals (the money part of the batch was booked in the same cycle). */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        boolean transfer = instructions.findByInstructionId(e.instructionId())
                .map(o -> o.getType() == InstructionType.ANIMAL_TRANSFER).orElse(false);
        ServiceCase sc = cases.findById(e.relatedId()).orElse(null);
        if (!transfer || sc == null || sc.getStatus() != CaseStatus.IN_PROGRESS || !KINDS.contains(sc.getKind())) {
            return;
        }
        Savegame sg = sc.getSavegame();
        close(sc, CaseStatus.SETTLED, "DONE");
        Character n = sc.getCharacter();
        boolean bought = sc.getKind() == CaseKind.ANIMAL_OFFER;
        if (!bought) {
            addStock(sg, n, sc.getTitle(), sc.getQuantity());
        }
        trust.recordEvent(n, trade().getTradeTrustDelta(), TrustReason.NEIGHBOR_TRADE, sc.getReference());
        diary.addAuto(sg, "TRADE", "Viehhandel mit " + n.getName(), sc.getQuantity() + " " + sc.getReference()
                + (bought ? " von " + n.getName() + " gekauft" : " an " + n.getName() + " verkauft") + " ("
                + sc.getOfferAmount() + " €).", RELATED, sc.getId());
        narration.request(sg, NarrationEventType.ANIMAL_TRADE_DONE).from(n).facts(facts(sc).put("bought", bought).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).submit();
        gossip(sg, sc, bought);
    }

    /**
     * Roadmap "die Nachbarn sind persönlicher, und es gibt Klatsch": one message of another active villager after a
     * deal, like the gossip after a machine sale (R3-V3).
     */
    void gossip(Savegame sg, ServiceCase sc, boolean bought) {
        Character n = sc.getCharacter();
        List<Character> dyn = lookup.activeDynamic(sg).stream().filter(c -> n == null || !c.getId().equals(n.getId())).toList();
        Optional<Character> teller = dyn.isEmpty() ? lookup.firstActive(sg, CharacterRole.VILLAGER) : Optional.of(random.pick(dyn));
        teller.ifPresent(t -> narration.request(sg, NarrationEventType.ANIMAL_TRADE_GOSSIP).from(t)
                .facts(NarrationFacts.builder().put("neighborName", n == null ? null : n.getName())
                        .put("count", sc.getQuantity()).put("subType", sc.getReference()).put("bought", bought)
                        .put("dealText", (bought ? "von " : "an ") + (n == null ? "einem Nachbarn" : n.getName())
                                + (bought ? " gekauft" : " verkauft")).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit());
    }

    /**
     * FailedInstructionService: the mod did not move the animals. A purchase gives the reserved stock back; a sale with
     * too few animals in the stable disappoints the neighbour. Returns true when the case was open.
     */
    @Transactional
    public boolean onInstructionFailed(Long caseId, String message) {
        ServiceCase sc = cases.findById(caseId).orElse(null);
        if (sc == null || sc.getStatus() != CaseStatus.IN_PROGRESS || !KINDS.contains(sc.getKind())) {
            return false;
        }
        Savegame sg = sc.getSavegame();
        String reason = message == null ? "FAILED" : message.contains("NO_ANIMAL_SPACE") ? "NO_ANIMAL_SPACE"
                : message.contains("NOT_ENOUGH_ANIMALS") ? "NOT_ENOUGH_ANIMALS"
                : message.contains("NOT_SUPPORTED") || message.contains("unknown type") ? "MOD_OUTDATED" : "FAILED";
        if (sc.getKind() == CaseKind.ANIMAL_OFFER) {
            addStock(sg, sc.getCharacter(), sc.getTitle(), sc.getQuantity());
        } else if ("NOT_ENOUGH_ANIMALS".equals(reason)) {
            trust.recordEvent(sc.getCharacter(), trade().getStockMissingTrustDelta(), TrustReason.NEIGHBOR_DISAPPOINTED,
                    sc.getReference());
        }
        close(sc, CaseStatus.EXPIRED, reason);
        narration.request(sg, NarrationEventType.ANIMAL_TRADE_FAILED).from(sc.getCharacter())
                .facts(facts(sc).put("reason", reason).build())
                .category(CommunicationCategory.TRADE).related(RELATED, sc.getId()).submit();
        return true;
    }

    // ------------------------------------------------------------------------------------------ queries

    public List<ServiceCase> cases(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, KINDS);
    }

    private FarmFacts facts(Savegame sg) {
        FarmFacts f = neighbors.latest(sg).orElse(null);
        if (stables(f).isEmpty()) {
            throw new BusinessRuleException("ANIMAL_NO_STABLES", "Der Mod meldet keine Ställe mit Rassen – bitte den Mod "
                    + "FS25_RPSim aktualisieren oder erst einen Stall bauen.");
        }
        return f;
    }

    private Stable stable(FarmFacts f, String husbandryUniqueId) {
        return stables(f).stream().filter(s -> s.husbandryUniqueId().equals(husbandryUniqueId)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("ANIMAL_STABLE", "Diesen Stall gibt es nicht (mehr)."));
    }

    private Character neighbor(Savegame sg, Long id) {
        return characters.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .filter(NeighborService::isNeighbor).orElseThrow(() -> new NotFoundException("neighbour " + id));
    }
}
