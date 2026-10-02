package de.farmpulse.rpsim.farmwork;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.neighbor.NeighborService;
import de.farmpulse.rpsim.neighbor.NeighborTradeService;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-A1: the contractor ({@code CONTRACTOR}) works an own field (owner decisions in QUESTIONS.md).
 * <ul>
 *   <li>Offer: only works that fit the field state (R2-C1): plow / cultivate in EMPTY, HARVESTED or WITHERED, lime in
 *   EMPTY or HARVESTED with lime level 0, sow in EMPTY, harvest in HARVESTABLE - and a harvest only when the own silos
 *   take the whole yield. Price = hectares x price per hectare (material included).</li>
 *   <li>Order: the work day is 1-3 game days ahead (busy season +2, trust from the threshold -1, at least 1). Money,
 *   field and silo are only checked; nothing is booked.</li>
 *   <li>Work day: checked again, then one batch FIELD_WORK (+ STORAGE_TRANSFER IN of the harvest) + CONTRACTOR_FEE.
 *   No money, a field that no longer fits or a refusal of the mod cancel the job with a mail; nothing is booked.</li>
 *   <li>Done (ack of FIELD_WORK, for a harvest of the STORAGE_TRANSFER): trust, mail, diary.</li>
 * </ul>
 * The harvest = area x litersPerSqm x yield factor (fertilisation, lime, plow, weeds; {@link #yieldFactor}) - not the
 * formula of the game.
 */
@Service
public class ContractorWorkService {

    public static final String RELATED = "CONTRACTOR_WORK";
    public static final List<String> WORKS = List.of("PLOW", "CULTIVATE", "LIME", "SOW", "HARVEST");
    static final Map<String, String> WORK_TITLES = Map.of("PLOW", "Pflügen", "CULTIVATE", "Grubbern", "LIME", "Kalken",
            "SOW", "Säen", "HARVEST", "Ernten");

    /** One work for the form; {@code reason} names why it is not possible (null = possible). */
    public record Option(String work, long price, Long harvestLiters, String fillType, String reason) {
        public boolean possible() {
            return reason == null;
        }
    }

    /** The form of one own field: works, fruit types for sowing and the range of the work day. */
    public record Quote(int farmlandId, String fieldName, double hectares, FieldPhase phase, List<Option> options,
                        List<String> fruitTypes, int daysMin, int daysMax, ServiceCase openOrder) {
    }

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final OutboxInstructionRepository instructions;
    private final FactsService facts;
    private final FieldService fields;
    private final NeighborService neighbors;
    private final NeighborTradeService trade;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final FallbackTemplates labels;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public ContractorWorkService(ServiceCaseRepository cases, SavegameRepository savegames,
                                 OutboxInstructionRepository instructions, FactsService facts, FieldService fields,
                                 NeighborService neighbors, NeighborTradeService trade, LiquidityService liquidity,
                                 OutboxService outbox, ServiceRoleService roles, TrustScoreService trust,
                                 NarrationRequestService narration, DiaryService diary, FallbackTemplates labels,
                                 RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.cases = cases;
        this.savegames = savegames;
        this.instructions = instructions;
        this.facts = facts;
        this.fields = fields;
        this.neighbors = neighbors;
        this.trade = trade;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.roles = roles;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.labels = labels;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.ContractorWork cfg() {
        return props.getFormulas().getContractorWork();
    }

    // ------------------------------------------------------------------------------------------ formulas

    /**
     * Yield factor of a contractor harvest (product): fertilisation by sprayLevel, lime level 0 and plow level 0 when the
     * savegame needs them, weeds per step (capped). Without {@code fieldRules} every rule counts as active.
     */
    public double yieldFactor(BridgeDtos.Field f, BridgeDtos.FieldRules rules) {
        RpsimProperties.ContractorWork.Yield y = cfg().getYield();
        double factor = 1;
        List<Double> spray = y.getSprayFactors();
        if (!spray.isEmpty()) {
            int level = f.sprayLevel() == null ? 0 : Math.max(0, f.sprayLevel());
            factor *= spray.get(Math.min(level, spray.size() - 1));
        }
        if (rules == null || Boolean.TRUE.equals(rules.limeRequired())) {
            if (f.limeLevel() != null && f.limeLevel() == 0) {
                factor *= y.getLimeMissingFactor();
            }
        }
        if (rules == null || Boolean.TRUE.equals(rules.plowingRequired())) {
            if (f.plowLevel() != null && f.plowLevel() == 0) {
                factor *= y.getPlowMissingFactor();
            }
        }
        if (rules == null || Boolean.TRUE.equals(rules.weedsEnabled())) {
            int weeds = f.weedState() == null ? 0 : Math.max(0, f.weedState());
            factor *= 1 - Math.min(y.getWeedMax(), weeds * y.getWeedStep());
        }
        return factor;
    }

    /** Litres of a contractor harvest: hectares x 10 000 x litersPerSqm x factor; empty without a known yield. */
    public OptionalDouble harvestLiters(BridgeDtos.Field f, BridgeDtos.FieldRules rules) {
        OptionalDouble perSqm = fields.litersPerSqm(f);
        if (perSqm.isEmpty() || f.hectares() == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.floor(f.hectares() * 10_000 * perSqm.getAsDouble() * yieldFactor(f, rules)));
    }

    public long price(String work, double hectares) {
        return Math.round(hectares * cfg().getPricePerHa().getOrDefault(work, 0.0));
    }

    /** Range of the work day in game days: busy season +harvest-extra-days, trust from the threshold -days (min 1). */
    public int[] leadDays(Savegame sg, Character contractor) {
        int min = cfg().getLeadDaysMin();
        int max = Math.max(min, cfg().getLeadDaysMax());
        if (cfg().getHarvestPeriods().contains(gameTime.periodOfYear(sg, sg.getCurrentGameTime()))) {
            min += cfg().getHarvestExtraDays();
            max += cfg().getHarvestExtraDays();
        }
        if (contractor != null && trust.getCurrentTrust(contractor) >= cfg().getTrustThreshold()) {
            min = Math.max(1, min - cfg().getTrustDaysLess());
            max = Math.max(1, max - cfg().getTrustDaysLess());
        }
        return new int[] { Math.max(1, min), Math.max(1, max) };
    }

    // ------------------------------------------------------------------------------------------ offer

    /** The works for the field; {@code ownLiters} = litres this field's open harvest already reserves in the silos. */
    List<Option> options(Savegame sg, FarmFacts f, BridgeDtos.Field field, long ownLiters) {
        FieldPhase phase = FieldService.phase(field);
        double ha = field.hectares() == null ? 0 : field.hectares();
        List<Option> list = new ArrayList<>();
        for (String work : WORKS) {
            long price = price(work, ha);
            String reason = switch (work) {
                case "PLOW", "CULTIVATE" -> phase == FieldPhase.EMPTY || phase == FieldPhase.HARVESTED
                        || phase == FieldPhase.WITHERED ? null : "PHASE";
                case "LIME" -> !(phase == FieldPhase.EMPTY || phase == FieldPhase.HARVESTED) ? "PHASE"
                        : field.limeLevel() == null || field.limeLevel() != 0 ? "LIMED" : null;
                case "SOW" -> phase == FieldPhase.EMPTY ? null : "PHASE";
                default -> phase == FieldPhase.HARVESTABLE ? null : "PHASE";
            };
            Long liters = null;
            String fillType = null;
            if ("HARVEST".equals(work) && reason == null) {
                fillType = field.fillType() != null ? field.fillType() : field.fruitType();
                OptionalDouble l = harvestLiters(field, f.fieldRules());
                if (l.isEmpty() || fillType == null) {
                    reason = "NO_YIELD";
                } else {
                    liters = Math.round(l.getAsDouble());
                    if (f.tradeStorage() == null) {
                        reason = "NO_SILOS";
                    } else if (trade.freeCapacity(sg, neighbors.tradeStorage(f), fillType) + ownLiters < liters) {
                        reason = "NO_CAPACITY";
                    }
                }
            }
            list.add(new Option(work, price, liters, fillType, reason));
        }
        return list;
    }

    /** Form "Lohnunternehmer beauftragen" for an own field. */
    @Transactional
    public Quote quote(Savegame sg, int farmlandId) {
        FarmFacts f = requireFields(sg);
        BridgeDtos.Field field = ownField(f, farmlandId);
        Character contractor = roles.ensure(sg, CharacterRole.CONTRACTOR);
        int[] days = leadDays(sg, contractor);
        Optional<ServiceCase> open = openOrder(sg, farmlandId);
        List<Option> options = options(sg, f, field, 0);
        if (open.isPresent()) {
            options = options.stream().map(o -> new Option(o.work(), o.price(), o.harvestLiters(), o.fillType(),
                    "OPEN_ORDER")).toList();
        }
        return new Quote(farmlandId, field.name(), field.hectares() == null ? 0 : field.hectares(),
                FieldService.phase(field), options, cfg().getSowFruitTypes(), days[0], days[1], open.orElse(null));
    }

    /** The player orders a work: checked, the work day is rolled; nothing is booked until that day. */
    @Transactional
    public ServiceCase order(Savegame sg, int farmlandId, String work, String fruitType) {
        if (!cfg().isEnabled()) {
            throw new BusinessRuleException("CONTRACTOR_OFF", "Der Lohnunternehmer nimmt gerade keine Aufträge an.");
        }
        if (!WORKS.contains(work)) {
            throw new BusinessRuleException("CONTRACTOR_WORK", "Unbekannte Arbeit: " + work);
        }
        FarmFacts f = requireFields(sg);
        BridgeDtos.Field field = ownField(f, farmlandId);
        if (openOrder(sg, farmlandId).isPresent()) {
            throw new BusinessRuleException("CONTRACTOR_OPEN", "Für dieses Feld ist schon ein Auftrag offen.");
        }
        Option option = options(sg, f, field, 0).stream().filter(o -> o.work().equals(work)).findFirst().orElseThrow();
        if (!option.possible()) {
            throw new BusinessRuleException("CONTRACTOR_" + option.reason(), refusal(option, field));
        }
        String title = null;
        if ("SOW".equals(work)) {
            if (fruitType == null || !cfg().getSowFruitTypes().contains(fruitType)) {
                throw new BusinessRuleException("CONTRACTOR_FRUIT", "Bitte eine Fruchtsorte aus der Liste wählen.");
            }
            title = fruitType;
        } else if ("HARVEST".equals(work)) {
            title = option.fillType();
        }
        if (liquidity.available(sg) < option.price()) {
            throw new BusinessRuleException("CONTRACTOR_FUNDS", "Dafür reicht dein Kontostand nicht (" + option.price()
                    + " €).");
        }
        Character contractor = roles.ensure(sg, CharacterRole.CONTRACTOR);
        int[] range = leadDays(sg, contractor);
        int days = random.intBetween(range[0], range[1]);
        long now = sg.getCurrentGameTime();
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.CONTRACTOR_WORK);
        sc.setStatus(CaseStatus.IN_PROGRESS);
        sc.setCharacter(contractor);
        sc.setFarmlandId(farmlandId);
        sc.setHectares(field.hectares());
        sc.setReference(work);
        sc.setTitle(title);
        sc.setQuantity(option.harvestLiters() == null ? null : option.harvestLiters().intValue());
        sc.setOfferAmount(option.price());
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(days));
        sc.setCreatedAt(java.time.Instant.now());
        return cases.save(sc);
    }

    private String refusal(Option o, BridgeDtos.Field field) {
        return switch (o.reason()) {
            case "NO_CAPACITY" -> "Wohin mit dem " + labels.label(o.fillType()) + "? In deinen Silos ist nicht genug Platz für "
                    + o.harvestLiters() + " Liter.";
            case "NO_SILOS" -> "Der Mod meldet deine Silos nicht – bitte den Mod FS25_RPSim aktualisieren.";
            case "NO_YIELD" -> "Für dieses Feld ist kein Ertrag bekannt.";
            case "LIMED" -> "Feld " + field.name() + " ist schon gekalkt.";
            default -> WORK_TITLES.get(o.work()) + " passt gerade nicht zum Zustand von Feld " + field.name() + ".";
        };
    }

    // ------------------------------------------------------------------------------------------ work day

    @EventListener
    @Order(82)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS)) {
            if (sc.getKind() == CaseKind.CONTRACTOR_WORK && sc.getExternalId() == null && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() <= now) {
                execute(sg, sc);
            }
        }
    }

    /** The work day: field, silo and money are checked again, then the batch goes to the mod. */
    void execute(Savegame sg, ServiceCase sc) {
        FarmFacts f = facts.latest(sg).orElse(null);
        BridgeDtos.Field field = f == null || f.fields() == null ? null : f.fields().stream()
                .filter(x -> x != null && Integer.valueOf(sc.getFarmlandId()).equals(x.farmlandId())).findFirst()
                .orElse(null);
        if (field == null) {
            cancel(sc, "NOT_OWN_FIELD");
            return;
        }
        long reserved = sc.getQuantity() == null ? 0 : sc.getQuantity();
        Option option = options(sg, f, field, "HARVEST".equals(sc.getReference()) ? reserved : 0).stream()
                .filter(o -> o.work().equals(sc.getReference())).findFirst().orElseThrow();
        if (!option.possible()) {
            cancel(sc, "NO_CAPACITY".equals(option.reason()) ? "NO_CAPACITY" : "NOT_NEEDED");
            return;
        }
        if (liquidity.available(sg) < sc.getOfferAmount()) {
            cancel(sc, "NO_FUNDS");
            return;
        }
        boolean harvest = "HARVEST".equals(sc.getReference());
        long liters = harvest ? option.harvestLiters() : 0;
        if (harvest) {
            sc.setQuantity((int) liters);
            sc.setTitle(option.fillType());
        }
        String note = WORK_TITLES.get(sc.getReference()) + " Feld " + field.name();
        List<OutboxInstruction> batch = outbox.fieldWorkDeal(sg, sc.getFarmlandId(), sc.getReference(),
                "SOW".equals(sc.getReference()) ? sc.getTitle() : null, harvest ? option.fillType() : null, liters,
                sc.getOfferAmount(), note, new Related(RELATED, sc.getId()));
        sc.setExternalId(batch.get(0).getBatchId());
    }

    /** Sentence of the cancellation mail per reason (the numbers come from the facts, never from the AI). */
    static final Map<String, String> CANCEL_TEXTS = Map.of(
            "NO_FUNDS", "Dein Kontostand hat am Arbeitstag nicht für die Rechnung gereicht.",
            "NO_CAPACITY", "In deinen Silos war am Arbeitstag nicht genug Platz für die Ernte.",
            "NOT_NEEDED", "Das Feld passte am Arbeitstag nicht mehr zu der Arbeit.",
            "NOT_OWN_FIELD", "Das Feld gehört dir nicht mehr.",
            "MOD_OUTDATED", "Der Mod FS25_RPSim kennt die Arbeit noch nicht – bitte den Mod aktualisieren.",
            "FAILED", "Im Spiel hat es nicht geklappt.");

    private void cancel(ServiceCase sc, String reason) {
        close(sc, CaseStatus.EXPIRED, reason);
        narration.request(sc.getSavegame(), NarrationEventType.CONTRACTOR_WORK_CANCELLED).from(sc.getCharacter())
                .facts(facts(sc).put("reason", reason).put("reasonText", CANCEL_TEXTS.getOrDefault(reason, "")).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, sc.getId()).submit();
    }

    private void close(ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sc.getSavegame().getCurrentGameTime());
    }

    private NarrationFacts.Builder facts(ServiceCase sc) {
        NarrationFacts.Builder b = NarrationFacts.builder().put("work", WORK_TITLES.get(sc.getReference()))
                .put("farmlandId", sc.getFarmlandId()).put("price", sc.getOfferAmount())
                .put("hectares", sc.getHectares() == null ? 0 : sc.getHectares());
        if ("SOW".equals(sc.getReference()) && sc.getTitle() != null) {
            b.put("fruitType", labels.label(sc.getTitle()));
        }
        if ("HARVEST".equals(sc.getReference()) && sc.getTitle() != null) {
            b.put("fillType", labels.label(sc.getTitle())).put("liters", sc.getQuantity() == null ? 0 : sc.getQuantity());
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------ bridge acks

    /** Done: the FIELD_WORK (for a harvest the STORAGE_TRANSFER of the yield) is acknowledged APPLIED. */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || !RELATED.equals(e.relatedType()) || e.relatedId() == null) {
            return;
        }
        ServiceCase sc = cases.findById(e.relatedId()).orElse(null);
        if (sc == null || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return;
        }
        InstructionType done = "HARVEST".equals(sc.getReference()) ? InstructionType.STORAGE_TRANSFER
                : InstructionType.FIELD_WORK;
        boolean last = instructions.findByInstructionId(e.instructionId()).map(o -> o.getType() == done).orElse(false);
        if (!last) {
            return;
        }
        close(sc, CaseStatus.SETTLED, "DONE");
        Savegame sg = sc.getSavegame();
        trust.recordEvent(sc.getCharacter(), cfg().getDoneTrustDelta(), TrustReason.CONTRACTOR_WORK,
                WORK_TITLES.get(sc.getReference()));
        String what = WORK_TITLES.get(sc.getReference()) + " auf Feld " + sc.getFarmlandId();
        String detail = "HARVEST".equals(sc.getReference()) && sc.getQuantity() != null
                ? ", " + sc.getQuantity() + " l " + labels.label(sc.getTitle()) + " eingelagert"
                : "SOW".equals(sc.getReference()) && sc.getTitle() != null ? " (" + labels.label(sc.getTitle()) + ")" : "";
        diary.addAuto(sg, "FARM_WORK", "Lohnunternehmer: " + what, what + detail + " – Rechnung " + sc.getOfferAmount()
                + " €.", RELATED, sc.getId());
        narration.request(sg, NarrationEventType.CONTRACTOR_WORK_DONE).from(sc.getCharacter()).facts(facts(sc).build())
                .category(CommunicationCategory.CONTRACT).related(RELATED, sc.getId()).submit();
    }

    /**
     * FailedInstructionService: the mod refused the work or the storage of the harvest - the job is cancelled, the fee
     * of the batch was not booked. Returns true when the case was open.
     */
    @Transactional
    public boolean onInstructionFailed(Long caseId, String message) {
        ServiceCase sc = cases.findById(caseId).orElse(null);
        if (sc == null || sc.getKind() != CaseKind.CONTRACTOR_WORK || sc.getStatus() != CaseStatus.IN_PROGRESS) {
            return false;
        }
        cancel(sc, message != null && message.contains("NOT_SUPPORTED") ? "MOD_OUTDATED" : "FAILED");
        return true;
    }

    // ------------------------------------------------------------------------------------------ queries

    public List<ServiceCase> orders(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.CONTRACTOR_WORK));
    }

    Optional<ServiceCase> openOrder(Savegame sg, int farmlandId) {
        return cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS).stream()
                .filter(c -> c.getKind() == CaseKind.CONTRACTOR_WORK && Integer.valueOf(farmlandId).equals(c.getFarmlandId()))
                .findFirst();
    }

    /** Open order per farmland (Flurkarte badge). */
    public Map<Integer, ServiceCase> openOrders(Savegame sg) {
        Map<Integer, ServiceCase> m = new LinkedHashMap<>();
        for (ServiceCase c : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS)) {
            if (c.getKind() == CaseKind.CONTRACTOR_WORK && c.getFarmlandId() != null) {
                m.putIfAbsent(c.getFarmlandId(), c);
            }
        }
        return m;
    }

    private FarmFacts requireFields(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.fields() == null) {
            throw new BusinessRuleException("CONTRACTOR_NO_FIELDS", "Der Mod meldet deine Felder nicht – bitte den Mod "
                    + "FS25_RPSim aktualisieren.");
        }
        return f;
    }

    private static BridgeDtos.Field ownField(FarmFacts f, int farmlandId) {
        return f.fields().stream().filter(x -> x != null && Integer.valueOf(farmlandId).equals(x.farmlandId())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("CONTRACTOR_FIELD", "Feld " + farmlandId
                        + " ist kein eigenes Feld."));
    }
}
