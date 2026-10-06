package de.farmpulse.rpsim.farmwork;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   EMPTY or HARVESTED with lime level 0, sow in EMPTY, fertilise in EMPTY, HARVESTED or GROWING below the highest
 *   spray level, harvest in HARVESTABLE - and a harvest only when the own silos take the whole yield. Price = hectares
 *   x price per hectare (material included).</li>
 *   <li>Order (owner decisions 2026-10-06): 1 to {@code max-works-per-order} works of one field at once, done in the
 *   order of {@link #WORKS} on the same day; every work is checked on the field as the works before leave it (e.g.
 *   cultivate the stubble, then sow, then fertilise). Each work is its own case. The work is done at the end of the game
 *   day after the order. Money, field and silo are only checked; nothing is booked.</li>
 *   <li>Work day: checked again work by work, then one batch with FIELD_WORK (+ STORAGE_TRANSFER IN of the harvest) +
 *   CONTRACTOR_FEE per work. No money, a field that no longer fits or a refusal of the mod cancel the job with a mail;
 *   nothing of it is booked.</li>
 *   <li>Done (ack of FIELD_WORK, for a harvest of the STORAGE_TRANSFER): trust, mail, diary.</li>
 * </ul>
 * The harvest = area x litersPerSqm x yield factor (fertilisation, lime, plow, weeds; {@link #yieldFactor}) - not the
 * formula of the game.
 */
@Service
public class ContractorWorkService {

    public static final String RELATED = "CONTRACTOR_WORK";
    /** The works in the order the contractor does them on one day (harvest first, fertilising after sowing). */
    public static final List<String> WORKS = List.of("HARVEST", "PLOW", "CULTIVATE", "LIME", "SOW", "FERTILIZE");
    static final Map<String, String> WORK_TITLES = Map.of("PLOW", "Pflügen", "CULTIVATE", "Grubbern", "LIME", "Kalken",
            "SOW", "Säen", "FERTILIZE", "Düngen", "HARVEST", "Ernten");

    /** One work for the form; {@code reason} names why it is not possible (null = possible). */
    public record Option(String work, long price, Long harvestLiters, String fillType, String reason) {
        public boolean possible() {
            return reason == null;
        }
    }

    /**
     * The form of one own field: works (checked together with the {@code selected} ones), fruit types for sowing, the
     * most works per order, the game time the work is done by and the open order (one case per work).
     */
    public record Quote(int farmlandId, String fieldName, double hectares, FieldPhase phase, List<Option> options,
                        List<String> selected, List<String> fruitTypes, int maxWorks, long doneByGameTime,
                        List<ServiceCase> openOrders) {
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
    private final RpsimProperties props;

    public ContractorWorkService(ServiceCaseRepository cases, SavegameRepository savegames,
                                 OutboxInstructionRepository instructions, FactsService facts, FieldService fields,
                                 NeighborService neighbors, NeighborTradeService trade, LiquidityService liquidity,
                                 OutboxService outbox, ServiceRoleService roles, TrustScoreService trust,
                                 NarrationRequestService narration, DiaryService diary, FallbackTemplates labels,
                                 RpsimProperties props) {
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
        this.props = props;
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

    /**
     * Work day (owner decision 2026-10-06): the work is done at the end of the game day {@code done-after-days} days
     * after the order day - the batch goes to the mod when that day ends (start of the following game day).
     */
    public long doneBy(long orderGameTime) {
        return (GameTime.dayIndex(orderGameTime) + 1 + Math.max(0, cfg().getDoneAfterDays())) * GameTime.MS_PER_DAY;
    }

    public int maxWorks() {
        return Math.max(1, cfg().getMaxWorksPerOrder());
    }

    /**
     * The field as the work leaves it (the end state the mod sets, {@code RPSimGameAdapter:fieldWork}), to check the
     * next work of the same order: plow / cultivate clear the crop, lime sets the lime level, sowing starts the crop,
     * fertilising raises the spray level by one, the harvest cuts the crop.
     */
    static BridgeDtos.Field after(BridgeDtos.Field f, String work, String fruitType) {
        String fruit = f.fruitType();
        Integer growth = f.growthState();
        Integer minHarvest = f.minHarvestingGrowthState();
        Integer maxHarvest = f.maxHarvestingGrowthState();
        Boolean withered = f.withered();
        Boolean cut = f.cut();
        String fillType = f.fillType();
        Double litersPerSqm = f.litersPerSqm();
        Integer spray = f.sprayLevel();
        Integer lime = f.limeLevel();
        Integer plow = f.plowLevel();
        String ground = f.groundType();
        String sprayType = f.sprayType();
        switch (work) {
            case "PLOW", "CULTIVATE", "SOW" -> {
                boolean sow = "SOW".equals(work);
                fruit = sow ? fruitType : null;
                fillType = sow ? fruitType : null;
                growth = sow ? 1 : 0;
                minHarvest = null;
                maxHarvest = null;
                withered = sow ? false : null;
                cut = sow ? false : null;
                litersPerSqm = null;
                ground = sow ? "SOWN" : "PLOW".equals(work) ? "PLOWED" : "CULTIVATED";
                if ("PLOW".equals(work)) {
                    plow = Math.max(1, plow == null ? 0 : plow);
                }
            }
            case "LIME" -> {
                lime = Math.max(1, lime == null ? 0 : lime);
                sprayType = "LIME";
            }
            case "FERTILIZE" -> {
                spray = (spray == null ? 0 : spray) + 1;
                sprayType = "FERTILIZER";
            }
            default -> cut = true; // HARVEST
        }
        return new BridgeDtos.Field(f.farmlandId(), f.name(), f.hectares(), fruit, growth, minHarvest, maxHarvest,
                f.weedState(), f.stoneLevel(), spray, lime, plow, ground, withered, cut, fillType, litersPerSqm, sprayType);
    }

    // ------------------------------------------------------------------------------------------ offer

    /** One work on the field as it is; {@code ownLiters} = litres this field's open harvest already reserves. */
    Option option(Savegame sg, FarmFacts f, BridgeDtos.Field field, String work, long ownLiters) {
        FieldPhase phase = FieldService.phase(field);
        double ha = field.hectares() == null ? 0 : field.hectares();
        long price = price(work, ha);
        String reason = switch (work) {
            case "PLOW", "CULTIVATE" -> phase == FieldPhase.EMPTY || phase == FieldPhase.HARVESTED
                    || phase == FieldPhase.WITHERED ? null : "PHASE";
            case "LIME" -> !(phase == FieldPhase.EMPTY || phase == FieldPhase.HARVESTED) ? "PHASE"
                    : field.limeLevel() == null || field.limeLevel() != 0 ? "LIMED" : null;
            case "SOW" -> phase == FieldPhase.EMPTY ? null : "PHASE";
            case "FERTILIZE" -> !(phase == FieldPhase.EMPTY || phase == FieldPhase.HARVESTED
                    || phase == FieldPhase.GROWING) ? "PHASE"
                    : field.sprayLevel() != null && field.sprayLevel() >= cfg().getMaxSprayLevel() ? "FERTILIZED" : null;
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
        return new Option(work, price, liters, fillType, reason);
    }

    /** The works for the field as it is (each on its own). */
    List<Option> options(Savegame sg, FarmFacts f, BridgeDtos.Field field, long ownLiters) {
        return WORKS.stream().map(w -> option(sg, f, field, w, ownLiters)).toList();
    }

    /** Known works without duplicates in the order the contractor does them ({@link #WORKS}). */
    static List<String> sequence(java.util.Collection<String> works) {
        return WORKS.stream().filter(works::contains).toList();
    }

    /**
     * The works of one order in {@link #WORKS} order, each checked on the field as the possible works before leave it
     * (a work that is not possible leaves the field unchanged).
     */
    List<Option> plan(Savegame sg, FarmFacts f, BridgeDtos.Field field, List<String> works, String fruitType) {
        List<Option> list = new ArrayList<>();
        BridgeDtos.Field state = field;
        for (String work : sequence(works)) {
            Option o = option(sg, f, state, work, 0);
            list.add(o);
            if (o.possible()) {
                state = after(state, work, fruitType);
            }
        }
        return list;
    }

    /** Form "Lohnunternehmer beauftragen" for an own field without a selection. */
    @Transactional
    public Quote quote(Savegame sg, int farmlandId) {
        return quote(sg, farmlandId, List.of());
    }

    /**
     * Form "Lohnunternehmer beauftragen" for an own field. {@code selected} = the works the player has ticked: those are
     * checked as one order; every other work as if it were ticked too ({@code MAX_WORKS} when the order is full,
     * {@code SEQUENCE} when it would break a ticked work, e.g. harvesting a field that is to be sown).
     */
    @Transactional
    public Quote quote(Savegame sg, int farmlandId, java.util.Collection<String> selected) {
        FarmFacts f = requireFields(sg);
        BridgeDtos.Field field = ownField(f, farmlandId);
        roles.ensure(sg, CharacterRole.CONTRACTOR);
        List<ServiceCase> open = openOrders(sg, farmlandId);
        List<String> sel = sequence(selected == null ? List.of() : selected);
        if (sel.size() > maxWorks()) {
            sel = sel.subList(0, maxWorks());
        }
        String fruit = cfg().getSowFruitTypes().isEmpty() ? null : cfg().getSowFruitTypes().get(0);
        Map<String, Option> planned = byWork(plan(sg, f, field, sel, fruit));
        List<Option> options = new ArrayList<>();
        for (String work : WORKS) {
            Option o;
            if (!open.isEmpty()) {
                o = withReason(option(sg, f, field, work, 0), "OPEN_ORDER");
            } else if (sel.contains(work)) {
                o = planned.get(work);
            } else if (sel.size() >= maxWorks()) {
                o = withReason(option(sg, f, field, work, 0), "MAX_WORKS");
            } else {
                List<String> with = new ArrayList<>(sel);
                with.add(work);
                Map<String, Option> p = byWork(plan(sg, f, field, with, fruit));
                o = p.get(work);
                boolean breaks = sel.stream().anyMatch(w -> planned.get(w).possible() && !p.get(w).possible());
                if (o.possible() && breaks) {
                    o = withReason(o, "SEQUENCE");
                }
            }
            options.add(o);
        }
        return new Quote(farmlandId, field.name(), field.hectares() == null ? 0 : field.hectares(),
                FieldService.phase(field), options, sel, cfg().getSowFruitTypes(), maxWorks(),
                doneBy(sg.getCurrentGameTime()), open);
    }

    private static Option withReason(Option o, String reason) {
        return new Option(o.work(), o.price(), o.harvestLiters(), o.fillType(), reason);
    }

    private static Map<String, Option> byWork(List<Option> options) {
        Map<String, Option> m = new LinkedHashMap<>();
        options.forEach(o -> m.put(o.work(), o));
        return m;
    }

    /** The player orders one work (see {@link #order(Savegame, int, List, String)}). */
    @Transactional
    public ServiceCase order(Savegame sg, int farmlandId, String work, String fruitType) {
        return order(sg, farmlandId, List.of(work), fruitType).get(0);
    }

    /**
     * The player orders 1 to {@code max-works-per-order} works of one field at once: checked one after the other on the
     * field as the works before leave it; one case per work, all done at the end of the next game day. Nothing is booked
     * until then. {@code fruitType} only for SOW.
     */
    @Transactional
    public List<ServiceCase> order(Savegame sg, int farmlandId, List<String> works, String fruitType) {
        if (!cfg().isEnabled()) {
            throw new BusinessRuleException("CONTRACTOR_OFF", "Der Lohnunternehmer nimmt gerade keine Aufträge an.");
        }
        if (works == null || works.isEmpty()) {
            throw new BusinessRuleException("CONTRACTOR_WORK", "Bitte mindestens eine Arbeit wählen.");
        }
        for (String work : works) {
            if (!WORKS.contains(work)) {
                throw new BusinessRuleException("CONTRACTOR_WORK", "Unbekannte Arbeit: " + work);
            }
        }
        List<String> sequence = sequence(works);
        if (sequence.size() > maxWorks()) {
            throw new BusinessRuleException("CONTRACTOR_MAX_WORKS", "Höchstens " + maxWorks()
                    + " Arbeiten je Feld auf einmal.");
        }
        FarmFacts f = requireFields(sg);
        BridgeDtos.Field field = ownField(f, farmlandId);
        if (!openOrders(sg, farmlandId).isEmpty()) {
            throw new BusinessRuleException("CONTRACTOR_OPEN", "Für dieses Feld ist schon ein Auftrag offen.");
        }
        if (sequence.contains("SOW") && (fruitType == null || !cfg().getSowFruitTypes().contains(fruitType))) {
            throw new BusinessRuleException("CONTRACTOR_FRUIT", "Bitte eine Fruchtsorte aus der Liste wählen.");
        }
        List<Option> plan = plan(sg, f, field, sequence, fruitType);
        for (int i = 0; i < plan.size(); i++) {
            Option option = plan.get(i);
            if (!option.possible()) {
                throw new BusinessRuleException("CONTRACTOR_" + option.reason(), refusal(option, field,
                        sequence.subList(0, i)));
            }
        }
        long total = plan.stream().mapToLong(Option::price).sum();
        if (liquidity.available(sg) < total) {
            throw new BusinessRuleException("CONTRACTOR_FUNDS", "Dafür reicht dein Kontostand nicht (" + total + " €).");
        }
        Character contractor = roles.ensure(sg, CharacterRole.CONTRACTOR);
        long now = sg.getCurrentGameTime();
        long doneBy = doneBy(now);
        List<ServiceCase> list = new ArrayList<>();
        for (Option option : plan) {
            ServiceCase sc = new ServiceCase();
            sc.setSavegame(sg);
            sc.setKind(CaseKind.CONTRACTOR_WORK);
            sc.setStatus(CaseStatus.IN_PROGRESS);
            sc.setCharacter(contractor);
            sc.setFarmlandId(farmlandId);
            sc.setHectares(field.hectares());
            sc.setReference(option.work());
            sc.setTitle("SOW".equals(option.work()) ? fruitType : "HARVEST".equals(option.work()) ? option.fillType() : null);
            sc.setQuantity(option.harvestLiters() == null ? null : option.harvestLiters().intValue());
            sc.setOfferAmount(option.price());
            sc.setGameTime(now);
            sc.setDeadlineGameTime(doneBy);
            sc.setCreatedAt(java.time.Instant.now());
            list.add(cases.save(sc));
        }
        return list;
    }

    private String refusal(Option o, BridgeDtos.Field field, List<String> before) {
        String after = before.isEmpty() ? "" : " nach " + String.join(", ", before.stream().map(WORK_TITLES::get).toList());
        return switch (o.reason()) {
            case "NO_CAPACITY" -> "Wohin mit dem " + labels.label(o.fillType()) + "? In deinen Silos ist nicht genug Platz für "
                    + o.harvestLiters() + " Liter.";
            case "NO_SILOS" -> "Der Mod meldet deine Silos nicht – bitte den Mod FS25_RPSim aktualisieren.";
            case "NO_YIELD" -> "Für dieses Feld ist kein Ertrag bekannt.";
            case "LIMED" -> "Feld " + field.name() + " ist schon gekalkt.";
            case "FERTILIZED" -> "Feld " + field.name() + " ist schon voll gedüngt.";
            default -> WORK_TITLES.get(o.work()) + " passt" + after + " nicht zum Zustand von Feld " + field.name() + ".";
        };
    }

    // ------------------------------------------------------------------------------------------ work day

    @EventListener
    @Order(82)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        Map<Integer, List<ServiceCase>> due = new LinkedHashMap<>();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS)) {
            if (sc.getKind() == CaseKind.CONTRACTOR_WORK && sc.getExternalId() == null && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() <= now) {
                due.computeIfAbsent(sc.getFarmlandId(), k -> new ArrayList<>()).add(sc);
            }
        }
        due.values().forEach(group -> execute(sg, group));
    }

    /**
     * The work day of one field: field, silo and money are checked again work by work (in {@link #WORKS} order, each
     * on the field as the works before leave it), then the works go to the mod as one batch - a refused work aborts the
     * works after it.
     */
    void execute(Savegame sg, List<ServiceCase> group) {
        List<ServiceCase> ordered = new ArrayList<>(group);
        ordered.sort(java.util.Comparator.comparingInt((ServiceCase c) -> WORKS.indexOf(c.getReference()))
                .thenComparing(ServiceCase::getId));
        FarmFacts f = facts.latest(sg).orElse(null);
        int farmlandId = ordered.get(0).getFarmlandId();
        BridgeDtos.Field field = f == null || f.fields() == null ? null : f.fields().stream()
                .filter(x -> x != null && Integer.valueOf(farmlandId).equals(x.farmlandId())).findFirst()
                .orElse(null);
        if (field == null) {
            ordered.forEach(sc -> cancel(sc, "NOT_OWN_FIELD"));
            return;
        }
        long available = liquidity.available(sg);
        String batchId = null;
        for (ServiceCase sc : ordered) {
            long reserved = sc.getQuantity() == null ? 0 : sc.getQuantity();
            boolean harvest = "HARVEST".equals(sc.getReference());
            Option option = option(sg, f, field, sc.getReference(), harvest ? reserved : 0);
            if (!option.possible()) {
                cancel(sc, "NO_CAPACITY".equals(option.reason()) ? "NO_CAPACITY" : "NOT_NEEDED");
                continue;
            }
            if (available < sc.getOfferAmount()) {
                cancel(sc, "NO_FUNDS");
                continue;
            }
            available -= sc.getOfferAmount();
            long liters = harvest ? option.harvestLiters() : 0;
            if (harvest) {
                sc.setQuantity((int) liters);
                sc.setTitle(option.fillType());
            }
            String note = WORK_TITLES.get(sc.getReference()) + " Feld " + field.name();
            List<OutboxInstruction> batch = outbox.fieldWorkDeal(sg, batchId, sc.getFarmlandId(), sc.getReference(),
                    "SOW".equals(sc.getReference()) ? sc.getTitle() : null, harvest ? option.fillType() : null, liters,
                    sc.getOfferAmount(), note, new Related(RELATED, sc.getId()));
            batchId = batch.get(0).getBatchId();
            sc.setExternalId(batchId);
            field = after(field, sc.getReference(), sc.getTitle());
        }
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
        boolean outdated = message != null && (message.contains("NOT_SUPPORTED") || message.contains("unknown work"));
        cancel(sc, outdated ? "MOD_OUTDATED" : "FAILED");
        return true;
    }

    // ------------------------------------------------------------------------------------------ queries

    public List<ServiceCase> orders(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.CONTRACTOR_WORK));
    }

    /** The open works of the field (one order, in the order the contractor does them). */
    List<ServiceCase> openOrders(Savegame sg, int farmlandId) {
        return cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS).stream()
                .filter(c -> c.getKind() == CaseKind.CONTRACTOR_WORK && Integer.valueOf(farmlandId).equals(c.getFarmlandId()))
                .sorted(java.util.Comparator.comparingInt((ServiceCase c) -> WORKS.indexOf(c.getReference())))
                .toList();
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
