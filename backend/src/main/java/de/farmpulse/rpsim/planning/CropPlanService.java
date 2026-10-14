package de.farmpulse.rpsim.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.BulkOrder;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CropPlan;
import de.farmpulse.rpsim.domain.CropPlan.Status;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.feed.FeedBalanceService;
import de.farmpulse.rpsim.field.FieldBookEvaluationService;
import de.farmpulse.rpsim.field.FieldMarginService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.BulkOrderRepository;
import de.farmpulse.rpsim.repository.CropPlanRepository;
import de.farmpulse.rpsim.repository.FieldBookEntryRepository;
import de.farmpulse.rpsim.repository.FieldRecordRepository;
import de.farmpulse.rpsim.repository.ForwardContractRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V4 section A: the crop planner (owner decisions 2026-10-13). One plan per field for the next season (the
 * running field book entry when nothing is sown in it yet), with hints (rotation, sowing window, storage, fertiliser
 * ordinance, soil rules), the forecast of yield, price, direct costs and margin, the needs of the plan (seed, marketing,
 * feed, liquidity), a task and calendar dates for the sowing window, the reminder of the cooperative, the comparison
 * of plan and reality once the season began and "Plan besprechen" once per plan year. Hints never block.
 */
@Service
public class CropPlanService {

    /** Fallow ("Brache"): the plan keeps the field empty. */
    public static final String FALLOW = "BRACHE";

    /** A crop of the map with its windows (null = not read, older mod). */
    public record CropOption(String name, String title, String fillType, List<String> products,
                             List<Integer> plantablePeriods, List<Integer> harvestablePeriods, Double litersPerSqm) {
    }

    /** A hint of the checks (A2): {@code code} with its values for the text. */
    public record Check(String code, Map<String, Object> values) {
    }

    /**
     * Forecast (A3): l/ha with its source (FIELD = this field, CROP = all fields, BASE = base yield), price per litre
     * (FORWARD = open forward contract, BEST = best current price, INTERNAL = purchase price x share), direct costs per
     * ha (MARGIN = average of D, SEED = seed only, the rest unknown) and the margin of the field. null = unknown.
     */
    public record Forecast(String fruitType, String fillType, Double hectares, Double litersPerHa, String yieldSource,
                           Double liters, Double pricePerLiter, String priceSource, Double revenue,
                           Double directCostsPerHa, String costSource, Double directCosts, Double margin) {
    }

    /** Sowing window of a plan: game time of its first month and of the end (exclusive); {@code active} = now in it. */
    public record Window(long start, long end, boolean active) {
    }

    /** {@code target} RUNNING = the running entry (nothing sown yet), NEXT = the season after it. */
    public record PlanView(long id, String status, String target, String fruitType, String fillType, Integer sowPeriod,
                           String note, List<Check> checks, Forecast forecast, Window window) {
    }

    /** One own field with its crops and its open plan (null = none). */
    public record FieldPlan(int farmlandId, String name, Double hectares, String runningCrop, String lastCrop,
                            boolean sown, PlanView plan, Forecast previous) {
    }

    public record SeedNeed(String fruitType, double hectares, Double liters, Double cost) {
    }

    /** Expected harvest of a fill type against the open forward contracts and bulk orders (A4). */
    public record MarketNeed(String fillType, double expected, double contracted, Double soldPercent) {
    }

    /** Expected harvest against the yearly need of a food group (F5); {@code suggest*} closes the gap. */
    public record FeedNeed(String animalType, String title, String kind, List<String> fillTypes, double yearNeed,
                           double expected, Double coveredPercent, String suggestCrop, Double suggestHectares) {
    }

    /** One finished or running season of a plan (A6): planned against reached. */
    public record PlanActual(long id, int farmlandId, String name, String status, String plannedCrop, String actualCrop,
                             Integer harvestYear, boolean ended, Double plannedLitersPerHa, Double actualLitersPerHa,
                             Double plannedMargin, Double actualMargin) {
    }

    public record Planning(Integer year, Integer period, boolean windows, boolean reminders, boolean discussed,
                           List<CropOption> crops, List<FieldPlan> fields, Double planMargin, Double previousMargin,
                           List<SeedNeed> seeds, List<MarketNeed> marketing, List<FeedNeed> feed,
                           List<PlanActual> history) {
    }

    /** "Fruchtfolge vorschlagen": crop and product with the reason (MARGIN = best margin of D, YIELD = yield x price). */
    public record Suggestion(String fruitType, String fillType, String reason, Double value) {
    }

    /** A sowing task (A5): field, crop and window. */
    public record SowDue(int farmlandId, String fieldName, String fruitType, String fillType, long start, long end) {
    }

    /**
     * Roadmap V4 R4-D5 (owner decision 2026-10-14): expected revenue of a running season - PLAN = the season matches its
     * plan or is not sown yet (the forecast of the plan, the values kept at sowing), ACTUAL = another crop than planned
     * stands (the A3 forecast of that crop).
     */
    public record ExpectedRevenue(String basis, String fruitType, String fillType, Double hectares, Double litersPerHa,
                                  String yieldSource, Double pricePerLiter, String priceSource, Double revenue) {
    }

    /** Planned harvest of one plan: fill type, litres and the start of its harvest window (null = unknown). */
    private record Harvest(String fillType, double liters, Long harvestStart) {
    }

    /** A planned expense in the liquidity plan (A4). */
    public record PlannedCost(String label, long amount) {
    }

    private final SavegameRepository savegames;
    private final CropPlanRepository plans;
    private final FieldBookEntryRepository entries;
    private final FactsService facts;
    private final FieldMarginService margins;
    private final FeedBalanceService feed;
    private final ForwardContractRepository forwardContracts;
    private final BulkOrderRepository bulkOrders;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final GameTime gameTime;
    private final RpsimProperties props;
    private final FieldRecordRepository fieldRecords;

    public CropPlanService(SavegameRepository savegames, CropPlanRepository plans, FieldBookEntryRepository entries,
                           FactsService facts, FieldMarginService margins, FeedBalanceService feed,
                           ForwardContractRepository forwardContracts, BulkOrderRepository bulkOrders,
                           CharacterLookup lookup, NarrationRequestService narration, GameTime gameTime,
                           RpsimProperties props, FieldRecordRepository fieldRecords) {
        this.fieldRecords = fieldRecords;
        this.savegames = savegames;
        this.plans = plans;
        this.entries = entries;
        this.facts = facts;
        this.margins = margins;
        this.feed = feed;
        this.forwardContracts = forwardContracts;
        this.bulkOrders = bulkOrders;
        this.lookup = lookup;
        this.narration = narration;
        this.gameTime = gameTime;
        this.props = props;
    }

    private RpsimProperties.CropPlanning cfg() {
        return props.getFormulas().getCropPlanning();
    }

    // ------------------------------------------------------------------------------------------ A1 plan

    /**
     * A1: the plan of a field for its next season (replaces an open plan). {@code fruitType} {@link #FALLOW} = fallow;
     * the product defaults to the standard product of the crop.
     */
    @Transactional
    public CropPlan plan(Savegame sg, int farmlandId, String fruitType, String fillType, Integer sowPeriod, String note) {
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.fields() == null || f.fields().stream().noneMatch(x -> x != null
                && Objects.equals(x.farmlandId(), farmlandId))) {
            throw new BusinessRuleException("CROP_PLAN_FIELD", "Nur eigene und gepachtete Felder lassen sich planen.");
        }
        if (fruitType == null || fruitType.isBlank()) {
            throw new BusinessRuleException("CROP_PLAN_CROP", "Bitte eine Kultur oder „Brache“ wählen.");
        }
        if (sowPeriod != null && (sowPeriod < 1 || sowPeriod > GameTime.PERIODS_PER_YEAR)) {
            throw new BusinessRuleException("CROP_PLAN_PERIOD", "Der Saatmonat muss zwischen 1 und 12 liegen.");
        }
        boolean fallow = FALLOW.equals(fruitType);
        CropOption crop = fallow ? null : crops(sg).get(fruitType);
        if (!fallow && crop == null) {
            throw new BusinessRuleException("CROP_PLAN_CROP", "Diese Kultur gibt es auf der Karte nicht.");
        }
        String product = fallow ? null : fillType == null || fillType.isBlank() ? crop.fillType() : fillType;
        if (!fallow && product != null && !product.equals(crop.fillType()) && !crop.products().contains(product)) {
            throw new BusinessRuleException("CROP_PLAN_PRODUCT", "Dieses Ernteprodukt passt nicht zur Kultur.");
        }
        CropPlan p = open(sg, farmlandId).orElseGet(() -> {
            CropPlan n = new CropPlan();
            n.setSavegame(sg);
            n.setFarmlandId(farmlandId);
            n.setStatus(Status.OPEN);
            return n;
        });
        FieldBookEntry running = running(sg, farmlandId).orElse(null);
        p.setCreatedGameTime(sg.getCurrentGameTime());
        p.setTargetEntryId(running != null && running.fruitType() == null ? running.getId() : null);
        p.setAfterEntryId(running != null && running.fruitType() != null ? running.getId() : null);
        p.setFruitType(fallow ? null : fruitType);
        p.setFillType(product);
        p.setSowPeriod(fallow ? null : sowPeriod);
        p.setNote(note == null || note.isBlank() ? null : note.trim());
        return plans.save(p);
    }

    @Transactional
    public void remove(Savegame sg, int farmlandId) {
        open(sg, farmlandId).ifPresent(plans::delete);
    }

    @Transactional
    public void setReminders(Savegame sg, boolean on) {
        sg.setCropPlanReminders(on);
    }

    private boolean reminders(Savegame sg) {
        return sg.getCropPlanReminders() == null ? cfg().isRemindersDefault() : sg.getCropPlanReminders();
    }

    private Optional<CropPlan> open(Savegame sg, int farmlandId) {
        return plans.findBySavegameAndStatus(sg, Status.OPEN).stream().filter(p -> p.getFarmlandId() == farmlandId)
                .findFirst();
    }

    private Optional<FieldBookEntry> running(Savegame sg, int farmlandId) {
        return entries.findBySavegameAndFarmlandIdOrderByIdAsc(sg, farmlandId).stream()
                .filter(e -> e.getStatus() == FieldBookEntry.Status.RUNNING).reduce((a, b) -> b);
    }

    /**
     * A1 / A6 after the field book: a field that is no longer own loses its open plan; when the season of a plan begins
     * with a crop the plan is MATCHED (the planned crop) or DEVIATED (another crop or a crop on a fallow plan) - no
     * penalty - and keeps the forecast of that moment.
     */
    @EventListener
    @Order(9)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.fields() == null) {
            return;
        }
        Set<Integer> own = new java.util.HashSet<>();
        f.fields().stream().filter(x -> x != null && x.farmlandId() != null).forEach(x -> own.add(x.farmlandId()));
        Context ctx = null;
        for (CropPlan p : plans.findBySavegameAndStatus(sg, Status.OPEN)) {
            if (!own.contains(p.getFarmlandId())) {
                plans.delete(p);
                continue;
            }
            FieldBookEntry season = running(sg, p.getFarmlandId()).orElse(null);
            long first = firstEntry(p);
            if (season == null || season.fruitType() == null || season.getId() < first) {
                continue;
            }
            if (ctx == null) {
                ctx = context(sg);
            }
            Forecast planned = forecast(ctx, p.getFarmlandId(), p.getFruitType(), p.getFillType());
            p.setStatus(Objects.equals(p.getFruitType(), season.fruitType()) ? Status.MATCHED : Status.DEVIATED);
            p.setEntryId(season.getId());
            p.setStartedGameTime(e.gameTime());
            p.setPlannedHectares(planned.hectares());
            p.setPlannedLitersPerHa(planned.litersPerHa());
            p.setPlannedYieldSource(planned.yieldSource());
            p.setPlannedPrice(planned.pricePerLiter());
            p.setPlannedMargin(planned.margin());
            plans.save(p);
        }
    }

    /** A rewind: a plan whose season began after the loaded time is open again. */
    @EventListener
    @Transactional
    public void onRewound(BridgeEvents.Rewound e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        for (CropPlan p : plans.findBySavegameOrderByIdAsc(sg)) {
            if (p.getStatus() != Status.OPEN && p.getStartedGameTime() != null && p.getStartedGameTime() > e.rewoundToGameTime()) {
                p.setStatus(Status.OPEN);
                p.setEntryId(null);
                p.setStartedGameTime(null);
                plans.save(p);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the view

    /** Everything the planner needs once per request. */
    private record Context(Savegame sg, FarmFacts facts, Map<String, CropOption> crops, Map<Integer, BridgeDtos.Field> fields,
                           List<FieldBookEntry> book, Map<String, Double> costs, Map<String, Double> bestPrices,
                           Map<String, Double> forwardPrices, List<FieldMarginService.CropMargin> cropMargins,
                           Integer fromYear) {
    }

    private Context context(Savegame sg) {
        return context(sg, true);
    }

    /** {@code withMargins} false: no direct costs of D (the margin card itself asks, which would go round in circles). */
    private Context context(Savegame sg, boolean withMargins) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Map<Integer, BridgeDtos.Field> fields = new TreeMap<>();
        if (f != null && f.fields() != null) {
            f.fields().stream().filter(x -> x != null && x.farmlandId() != null).forEach(x -> fields.put(x.farmlandId(), x));
        }
        Map<String, Double> forward = new HashMap<>();
        Map<String, double[]> sums = new HashMap<>();
        for (ForwardContract c : forwardContracts.findBySavegameAndStatus(sg, ForwardContract.OPEN)) {
            double[] s = sums.computeIfAbsent(c.getFillType(), k -> new double[2]);
            s[0] += c.getQuantity() * (double) c.getFixedPrice();
            s[1] += c.getQuantity();
        }
        double unit = props.getFormulas().getStorage().getPriceUnitLiters();
        sums.forEach((ft, s) -> {
            if (s[1] > 0) {
                forward.put(ft, s[0] / s[1] / unit);
            }
        });
        Map<String, Double> best = new HashMap<>();
        if (f != null) {
            FactsService.bestPrices(f).forEach((ft, price) -> best.put(ft, price / unit));
        }
        int defaultYears = Math.max(1, props.getFormulas().getFieldBook().getDefaultYears());
        Integer year = f != null && f.calendar() != null ? f.calendar().year() : null;
        return new Context(sg, f, crops(sg), fields, entries.findBySavegameOrderByIdAsc(sg), margins.purchasePrices(sg),
                best, forward, withMargins ? margins.margins(sg).crops() : List.of(),
                year == null ? null : year - defaultYears + 1);
    }

    /** The crops of the map (market_context.fruitTypes) by name. */
    private Map<String, CropOption> crops(Savegame sg) {
        Map<String, CropOption> out = new LinkedHashMap<>();
        facts.marketContext(sg).map(BridgeDtos.MarketContext::fruitTypes).ifPresent(list -> list.stream()
                .filter(t -> t != null && t.name() != null)
                .forEach(t -> out.put(t.name(), new CropOption(t.name(), t.title(), t.fillType(),
                        t.products() == null ? List.of() : t.products(), t.plantablePeriods(), t.harvestablePeriods(),
                        t.litersPerSqm()))));
        return out;
    }

    @Transactional(readOnly = true)
    public Planning planning(Savegame sg) {
        Context ctx = context(sg);
        long now = sg.getCurrentGameTime();
        GameTime.Anchor anchor = gameTime.anchor(sg);
        Integer year = ctx.facts() != null && ctx.facts().calendar() != null ? ctx.facts().calendar().year() : null;
        Integer period = ctx.facts() != null && ctx.facts().calendar() != null ? anchor.periodOf(anchor.monthIndex(now)) : null;
        Map<Integer, CropPlan> open = new HashMap<>();
        plans.findBySavegameAndStatus(sg, Status.OPEN).forEach(p -> open.put(p.getFarmlandId(), p));
        Map<Integer, Forecast> forecasts = new HashMap<>();
        open.values().forEach(p -> forecasts.put(p.getFarmlandId(), forecast(ctx, p.getFarmlandId(), p.getFruitType(),
                p.getFillType())));
        // planned harvest (owner decision 2026-10-14: open plans and MATCHED plans whose season still runs)
        List<Harvest> harvests = harvests(ctx, forecasts);
        Map<String, Double> expected = new TreeMap<>();
        harvests.forEach(h -> expected.merge(h.fillType(), h.liters(), Double::sum));
        Map<String, Double> contracted = contracted(sg);
        Set<String> storageShort = storageShort(ctx, harvests, contracts(sg));

        List<FieldPlan> fields = new ArrayList<>();
        double planSum = 0;
        double previousSum = 0;
        boolean planKnown = false;
        boolean previousKnown = false;
        for (BridgeDtos.Field field : ctx.fields().values()) {
            int id = field.farmlandId();
            FieldBookEntry running = ctx.book().stream().filter(x -> x.getFarmlandId() == id
                    && x.getStatus() == FieldBookEntry.Status.RUNNING).reduce((a, b) -> b).orElse(null);
            String last = lastCrop(ctx, id);
            CropPlan p = open.get(id);
            PlanView view = null;
            if (p != null) {
                Forecast fc = forecasts.get(id);
                boolean forRunning = running == null || running.fruitType() == null;
                view = new PlanView(p.getId(), p.getStatus().name(), forRunning ? "RUNNING" : "NEXT",
                        p.getFruitType() == null ? FALLOW : p.getFruitType(), p.getFillType(), p.getSowPeriod(),
                        p.getNote(), checks(ctx, p, field, running, fc, storageShort, period),
                        fc, window(sg, p, ctx.crops().get(p.getFruitType())));
                if (fc.margin() != null) {
                    planSum += fc.margin();
                    planKnown = true;
                }
            }
            String previousCrop = running != null && running.fruitType() != null ? running.fruitType() : last;
            Forecast previous = previousCrop == null ? null : forecast(ctx, id, previousCrop, null);
            if (p != null && previous != null && previous.margin() != null) {
                previousSum += previous.margin();
                previousKnown = true;
            }
            fields.add(new FieldPlan(id, field.name(), field.hectares(), running == null ? null : running.fruitType(),
                    last, running != null && running.fruitType() != null, view, previous));
        }
        boolean windows = ctx.crops().values().stream().anyMatch(c -> c.plantablePeriods() != null);
        Integer discussedYear = sg.getCropPlanDiscussedYear();
        return new Planning(year, period, windows, reminders(sg), year != null && year.equals(discussedYear),
                new ArrayList<>(ctx.crops().values()), fields, planKnown ? planSum : null,
                previousKnown ? previousSum : null, seeds(ctx, open.values(), forecasts),
                marketing(expected, contracted), feedNeeds(ctx, sg, expected), history(ctx, sg));
    }

    /** Main crop of the last finished entry of a field (null = none). */
    private static String lastCrop(Context ctx, int farmlandId) {
        return ctx.book().stream().filter(x -> x.getFarmlandId() == farmlandId
                        && x.getStatus() != FieldBookEntry.Status.RUNNING && x.fruitType() != null)
                .reduce((a, b) -> b).map(FieldBookEntry::fruitType).orElse(null);
    }

    // ------------------------------------------------------------------------------------------ A3 forecast

    /** A3: forecast of a field with a crop and product (product null = standard product); fallow = all zero. */
    Forecast forecast(Context ctx, int farmlandId, String fruitType, String fillType) {
        BridgeDtos.Field field = ctx.fields().get(farmlandId);
        Double ha = field != null && field.hectares() != null ? field.hectares()
                : ctx.book().stream().filter(x -> x.getFarmlandId() == farmlandId && x.getHectares() != null)
                .reduce((a, b) -> b).map(FieldBookEntry::getHectares).orElse(null);
        if (fruitType == null || FALLOW.equals(fruitType)) {
            return new Forecast(FALLOW, null, ha, 0.0, null, 0.0, null, null, 0.0, 0.0, null, 0.0, 0.0);
        }
        CropOption crop = ctx.crops().get(fruitType);
        String product = fillType != null ? fillType : crop != null ? crop.fillType() : null;
        // l/ha: this field, all fields, base yield
        Double lph = average(ctx, fruitType, product, farmlandId);
        String yieldSource = lph != null ? "FIELD" : null;
        if (lph == null) {
            lph = average(ctx, fruitType, product, null);
            yieldSource = lph != null ? "CROP" : null;
        }
        if (lph == null && crop != null && crop.litersPerSqm() != null && Objects.equals(product, crop.fillType())) {
            lph = crop.litersPerSqm() * 10_000 * cfg().getBaseYieldFactor();
            yieldSource = "BASE";
        }
        Double liters = lph != null && ha != null ? lph * ha : null;
        // price per litre: forward contract, best current price, internal value
        Double price = product == null ? null : ctx.forwardPrices().get(product);
        String priceSource = price != null ? "FORWARD" : null;
        if (price == null && product != null && ctx.bestPrices().get(product) != null) {
            price = ctx.bestPrices().get(product);
            priceSource = "BEST";
        }
        if (price == null && product != null && ctx.costs().get(product) != null) {
            price = ctx.costs().get(product) * props.getFormulas().getFieldMargin().getInternalPriceShare();
            priceSource = "INTERNAL";
        }
        Double revenue = liters != null && price != null ? liters * price : null;
        // direct costs per ha: average of D, else the seed alone
        Double costPerHa = ctx.cropMargins().stream().filter(m -> fruitType.equals(m.fruitType())
                        && (product == null || product.equals(m.fillType())) && m.directCostsPerHectare() != null)
                .map(m -> Math.abs(m.directCostsPerHectare())).findFirst().orElse(null);
        String costSource = costPerHa != null ? "MARGIN" : null;
        if (costPerHa == null) {
            Double seed = seedLitersPerHa(ctx, fruitType);
            Double seedPrice = ctx.costs().get("SEEDS");
            if (seed != null && seedPrice != null) {
                costPerHa = seed * seedPrice;
                costSource = "SEED";
            }
        }
        Double costs = costPerHa != null && ha != null ? costPerHa * ha : null;
        Double margin = revenue != null ? revenue - (costs == null ? 0 : costs) : null;
        return new Forecast(fruitType, product, ha, lph, yieldSource, liters, price, priceSource, revenue, costPerHa,
                costSource, costs, margin);
    }

    /** Average l/ha of the finished entries of the period of the field book evaluation (one field or all). */
    private static Double average(Context ctx, String fruitType, String fillType, Integer farmlandId) {
        double[] v = ctx.book().stream()
                .filter(x -> x.getStatus() != FieldBookEntry.Status.RUNNING && !x.isHeld() && x.getHarvestYear() != null)
                .filter(x -> ctx.fromYear() == null || x.getHarvestYear() >= ctx.fromYear())
                .filter(x -> farmlandId == null || x.getFarmlandId() == farmlandId)
                .filter(x -> fruitType.equals(x.fruitType()) && (fillType == null || fillType.equals(x.fillType())))
                .map(FieldBookEvaluationService::perHectare).filter(Objects::nonNull).mapToDouble(Double::doubleValue)
                .toArray();
        return v.length == 0 ? null : java.util.Arrays.stream(v).average().orElse(0);
    }

    private Double seedLitersPerHa(Context ctx, String fruitType) {
        return facts.marketContext(ctx.sg()).map(BridgeDtos.MarketContext::fruitTypes).flatMap(list -> list.stream()
                        .filter(t -> t != null && fruitType.equals(t.name()) && t.seedUsagePerSqm() != null).findFirst())
                .map(t -> t.seedUsagePerSqm() * 10_000).orElse(null);
    }

    // ------------------------------------------------------------------------------------------ A2 checks

    private List<Check> checks(Context ctx, CropPlan p, BridgeDtos.Field field, FieldBookEntry running, Forecast fc,
                               Set<String> storageShort, Integer period) {
        List<Check> out = new ArrayList<>();
        if (p.getFruitType() == null) {
            return out; // fallow: nothing to check
        }
        CropOption crop = ctx.crops().get(p.getFruitType());
        // rotation: the crop of the season before the planned one (running crop, else the last entry)
        List<String> history = ctx.book().stream().filter(x -> x.getFarmlandId() == p.getFarmlandId() && x.fruitType() != null
                && (x.getStatus() != FieldBookEntry.Status.RUNNING || Objects.equals(x.getId(), p.getAfterEntryId())))
                .map(FieldBookEntry::fruitType).toList();
        var authority = props.getFormulas().getAuthority();
        if (authority.isEnabled() && !history.isEmpty() && p.getFruitType().equals(history.getLast())) {
            double ha = fc.hectares() == null ? 0 : fc.hectares();
            out.add(new Check("ROTATION_SAME", values("hectares", round1(ha), "premium",
                    Math.round(ha * authority.getRotationPremiumPerHa()))));
            // the rule of the authority (AuthorityService.rotation, FieldOverviewService): the whole premium is cut once
            // a field repeats its crop for the second time (rotationViolations + 1 >= 2)
            int violations = fieldRecords.findBySavegameOrderByFarmlandIdAsc(ctx.sg()).stream()
                    .filter(r -> r.getFarmlandId() == p.getFarmlandId()).mapToInt(r -> r.getRotationViolations())
                    .findFirst().orElse(0);
            if (violations + 1 >= 2) {
                out.add(new Check("ROTATION_CUT", values("share", Math.round(authority.getRotationCutShare() * 100))));
            }
        }
        // sowing window
        if (crop != null && crop.plantablePeriods() != null) {
            if (p.getSowPeriod() != null && !crop.plantablePeriods().contains(p.getSowPeriod())) {
                out.add(new Check("SOW_OUTSIDE_WINDOW", values("period", p.getSowPeriod(), "periods",
                        crop.plantablePeriods())));
            }
            CropOption now = running == null || running.fruitType() == null ? null : ctx.crops().get(running.fruitType());
            if (period != null && p.getAfterEntryId() != null && now != null && now.harvestablePeriods() != null
                    && tooLate(period, now.harvestablePeriods(), crop.plantablePeriods())) {
                out.add(new Check("TOO_LATE", values("crop", running.fruitType())));
            }
        }
        // storage
        if (fc.fillType() != null && storageShort.contains(fc.fillType())) {
            out.add(new Check("STORAGE", values("fillType", fc.fillType())));
        }
        // fertiliser ordinance: autumn sowing
        var fertilizer = props.getFormulas().getFertilizerRules();
        Integer sow = p.getSowPeriod() != null ? p.getSowPeriod()
                : crop != null && crop.plantablePeriods() != null && !crop.plantablePeriods().isEmpty()
                ? crop.plantablePeriods().getFirst() : null;
        if (fertilizer.isEnabled() && sow != null && cfg().getAutumnPeriods().contains(sow)
                && !fertilizer.getExcludedFruitTypes().contains(p.getFruitType())) {
            out.add(new Check("FERTILIZER_CLOSED", values("periods", fertilizer.getClosedPeriods())));
        }
        // soil rules: lime and weeds not done in the season before the sowing
        BridgeDtos.FieldRules rules = ctx.facts() == null ? null : ctx.facts().fieldRules();
        FieldBookEntry before = running != null ? running : ctx.book().stream()
                .filter(x -> x.getFarmlandId() == p.getFarmlandId()).reduce((a, b) -> b).orElse(null);
        if (rules != null && before != null) {
            if (Boolean.TRUE.equals(rules.limeRequired()) && !before.limed()) {
                out.add(new Check("LIME", Map.of()));
            }
            if (Boolean.TRUE.equals(rules.weedsEnabled()) && !before.weeds()) {
                out.add(new Check("WEEDS", Map.of()));
            }
        }
        return out;
    }

    /**
     * The running crop can be harvested only after the last sowing month of the planned crop in the coming twelve
     * months (counted from the current period): "zu spät für dieses Jahr".
     */
    static boolean tooLate(int period, List<Integer> harvestable, List<Integer> plantable) {
        Integer firstHarvest = null;
        Integer lastSow = null;
        for (int k = 0; k < GameTime.PERIODS_PER_YEAR; k++) {
            int p = (period - 1 + k) % GameTime.PERIODS_PER_YEAR + 1;
            if (firstHarvest == null && harvestable.contains(p)) {
                firstHarvest = k;
            }
            if (plantable.contains(p)) {
                lastSow = k;
            }
        }
        return firstHarvest != null && lastSow != null && firstHarvest > lastSow;
    }

    /**
     * Fill types whose planned harvest exceeds the free capacity of the own silos plus what is sold before the harvest:
     * the open forward contracts and bulk orders whose delivery deadline ends before the harvest window of the fill
     * type begins (owner decision 2026-10-14). Only fill types an own silo takes are checked (tradeStorage); bunker silos
     * have no capacity in FS25 (BunkerSilo keeps a heap, no capacity), so silage and chaff in them cannot be checked.
     */
    private static Set<String> storageShort(Context ctx, List<Harvest> harvests, List<Contracted> contracts) {
        Set<String> out = new java.util.HashSet<>();
        if (ctx.facts() == null || ctx.facts().tradeStorage() == null) {
            return out;
        }
        Map<String, Double> free = new HashMap<>();
        ctx.facts().tradeStorage().stream().filter(t -> t != null && t.fillType() != null)
                .forEach(t -> free.merge(t.fillType(), t.freeCapacity() == null ? 0 : t.freeCapacity(), Double::sum));
        Map<String, Double> liters = new HashMap<>();
        Map<String, Long> start = new HashMap<>();
        for (Harvest h : harvests) {
            liters.merge(h.fillType(), h.liters(), Double::sum);
            if (h.harvestStart() != null) {
                start.merge(h.fillType(), h.harvestStart(), Math::min);
            }
        }
        liters.forEach((ft, l) -> {
            if (!free.containsKey(ft)) {
                return;
            }
            Long harvest = start.get(ft);
            double soldBefore = harvest == null ? 0 : contracts.stream()
                    .filter(c -> ft.equals(c.fillType()) && c.deadline() < harvest).mapToDouble(Contracted::remaining).sum();
            if (l > free.get(ft) + soldBefore) {
                out.add(ft);
            }
        });
        return out;
    }

    /**
     * The planned harvest: every open plan with its forecast and every MATCHED plan whose season still runs with the
     * values kept at sowing (owner decision 2026-10-14); a DEVIATED plan does not count. Harvest start = the first month
     * of the harvest window of the crop after its sowing window (open plans) or from now (sown).
     */
    private List<Harvest> harvests(Context ctx, Map<Integer, Forecast> openForecasts) {
        List<Harvest> out = new ArrayList<>();
        Map<Long, FieldBookEntry> byId = new HashMap<>();
        ctx.book().forEach(x -> byId.put(x.getId(), x));
        for (CropPlan p : plans.findBySavegameOrderByIdAsc(ctx.sg())) {
            if (p.getFruitType() == null) {
                continue;
            }
            CropOption crop = ctx.crops().get(p.getFruitType());
            if (p.getStatus() == Status.OPEN) {
                Forecast fc = openForecasts.get(p.getFarmlandId());
                if (fc == null) {
                    fc = forecast(ctx, p.getFarmlandId(), p.getFruitType(), p.getFillType());
                }
                if (fc.fillType() != null && fc.liters() != null) {
                    Window w = window(ctx.sg(), p, crop);
                    out.add(new Harvest(fc.fillType(), fc.liters(),
                            harvestStart(ctx.sg(), crop, w == null ? ctx.sg().getCurrentGameTime() : w.start(), w != null)));
                }
            } else if (p.getStatus() == Status.MATCHED && p.getEntryId() != null) {
                FieldBookEntry e = byId.get(p.getEntryId());
                if (e != null && e.getStatus() == FieldBookEntry.Status.RUNNING && p.getFillType() != null
                        && p.getPlannedLitersPerHa() != null && p.getPlannedHectares() != null) {
                    out.add(new Harvest(p.getFillType(), p.getPlannedLitersPerHa() * p.getPlannedHectares(),
                            harvestStart(ctx.sg(), crop, ctx.sg().getCurrentGameTime(), false)));
                }
            }
        }
        return out;
    }

    /** First month of the harvest window of a crop from {@code from} (after its month when {@code afterSowing}). */
    private Long harvestStart(Savegame sg, CropOption crop, long from, boolean afterSowing) {
        if (crop == null || crop.harvestablePeriods() == null || crop.harvestablePeriods().isEmpty()) {
            return null;
        }
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long m = anchor.monthIndex(from);
        for (int k = afterSowing ? 1 : 0; k <= GameTime.PERIODS_PER_YEAR; k++) {
            if (crop.harvestablePeriods().contains(anchor.periodOf(m + k))) {
                return k == 0 ? from : anchor.monthStart(m + k);
            }
        }
        return null;
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ------------------------------------------------------------------------------------------ A4 needs

    private List<SeedNeed> seeds(Context ctx, java.util.Collection<CropPlan> open, Map<Integer, Forecast> forecasts) {
        Map<String, double[]> byCrop = new TreeMap<>();
        Map<String, Boolean> known = new HashMap<>();
        Double seedPrice = ctx.costs().get("SEEDS");
        for (CropPlan p : open) {
            if (p.getFruitType() == null) {
                continue;
            }
            Forecast fc = forecasts.get(p.getFarmlandId());
            double ha = fc == null || fc.hectares() == null ? 0 : fc.hectares();
            Double perHa = seedLitersPerHa(ctx, p.getFruitType());
            double[] s = byCrop.computeIfAbsent(p.getFruitType(), k -> new double[2]);
            s[0] += ha;
            if (perHa != null) {
                s[1] += perHa * ha;
                known.put(p.getFruitType(), true);
            }
        }
        List<SeedNeed> out = new ArrayList<>();
        byCrop.forEach((crop, s) -> {
            Double liters = known.containsKey(crop) ? s[1] : null;
            out.add(new SeedNeed(crop, s[0], liters, liters == null || seedPrice == null ? null : liters * seedPrice));
        });
        return out;
    }

    /** An open forward contract or bulk order: litres still to deliver and the delivery deadline. */
    private record Contracted(String fillType, double remaining, long deadline) {
    }

    private List<Contracted> contracts(Savegame sg) {
        List<Contracted> out = new ArrayList<>();
        for (ForwardContract c : forwardContracts.findBySavegameAndStatus(sg, ForwardContract.OPEN)) {
            out.add(new Contracted(c.getFillType(), Math.max(0, c.getQuantity()
                    - (c.getDeliveredQuantity() == null ? 0 : c.getDeliveredQuantity())), c.getDeadlineGameTime()));
        }
        for (BulkOrder o : bulkOrders.findBySavegameAndStatus(sg, BulkOrder.OPEN)) {
            out.add(new Contracted(o.getFillType(), Math.max(0, o.getQuantity()
                    - (o.getDeliveredQuantity() == null ? 0 : o.getDeliveredQuantity())), o.getDeadlineGameTime()));
        }
        return out;
    }

    /** Open forward contracts and bulk orders per fill type (litres still to deliver). */
    private Map<String, Double> contracted(Savegame sg) {
        Map<String, Double> out = new HashMap<>();
        for (ForwardContract c : forwardContracts.findBySavegameAndStatus(sg, ForwardContract.OPEN)) {
            out.merge(c.getFillType(), (double) Math.max(0, c.getQuantity()
                    - (c.getDeliveredQuantity() == null ? 0 : c.getDeliveredQuantity())), Double::sum);
        }
        for (BulkOrder o : bulkOrders.findBySavegameAndStatus(sg, BulkOrder.OPEN)) {
            out.merge(o.getFillType(), (double) Math.max(0, o.getQuantity()
                    - (o.getDeliveredQuantity() == null ? 0 : o.getDeliveredQuantity())), Double::sum);
        }
        return out;
    }

    private static List<MarketNeed> marketing(Map<String, Double> expected, Map<String, Double> contracted) {
        List<MarketNeed> out = new ArrayList<>();
        expected.forEach((ft, liters) -> {
            double sold = contracted.getOrDefault(ft, 0.0);
            out.add(new MarketNeed(ft, liters, sold, liters > 0 ? Math.min(100, sold / liters * 100) : null));
        });
        return out;
    }

    /** A4: the expected harvest of the feed fill types (chaff counts as silage) against the yearly need of F5. */
    private List<FeedNeed> feedNeeds(Context ctx, Savegame sg, Map<String, Double> expected) {
        FeedBalanceService.Balance b = feed.balance(sg);
        List<FeedNeed> out = new ArrayList<>();
        if (!b.tracked()) {
            return out;
        }
        Map<String, Double> asFeed = new HashMap<>();
        expected.forEach((ft, liters) -> asFeed.merge(cfg().getFeedEquivalents().getOrDefault(ft, ft), liters, Double::sum));
        Map<String, List<String>> harvestCrops = props.getFormulas().getFeedBalance().getHarvestCrops();
        for (FeedBalanceService.AnimalBalance a : b.animals()) {
            for (FeedBalanceService.GroupBalance g : a.groups()) {
                if (g.yearNeed() <= 0) {
                    continue;
                }
                double got = g.fillTypes().stream().mapToDouble(ft -> asFeed.getOrDefault(ft, 0.0)).sum();
                String crop = null;
                Double hectares = null;
                double gap = g.yearNeed() - got;
                if (gap > 0) {
                    for (String ft : g.fillTypes()) {
                        for (String c : harvestCrops.getOrDefault(ft, List.of())) {
                            Double lph = suggestYield(ctx, c, ft);
                            if (lph != null && lph > 0 && crop == null) {
                                crop = c;
                                hectares = gap / lph;
                            }
                        }
                    }
                }
                out.add(new FeedNeed(a.animalType(), g.title(), g.kind(), g.fillTypes(), g.yearNeed(), got,
                        g.yearNeed() > 0 ? got / g.yearNeed() * 100 : null, crop, hectares));
            }
        }
        return out;
    }

    private Double suggestYield(Context ctx, String crop, String fillType) {
        Double lph = average(ctx, crop, null, null);
        if (lph == null) {
            CropOption c = ctx.crops().get(crop);
            lph = c == null || c.litersPerSqm() == null ? null : c.litersPerSqm() * 10_000 * cfg().getBaseYieldFactor();
        }
        return lph;
    }

    /**
     * A4: the seed and the expected direct costs of the open plans in the month their sowing window starts (the current
     * month when it is running), by month index - planned expenses of the liquidity plan.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<PlannedCost>> plannedByMonth(Savegame sg) {
        List<CropPlan> open = plans.findBySavegameAndStatus(sg, Status.OPEN);
        Map<Long, List<PlannedCost>> out = new HashMap<>();
        if (open.isEmpty()) {
            return out;
        }
        Context ctx = context(sg);
        GameTime.Anchor anchor = gameTime.anchor(sg);
        for (CropPlan p : open) {
            Window w = p.getFruitType() == null ? null : window(sg, p, ctx.crops().get(p.getFruitType()));
            if (w == null) {
                continue;
            }
            Forecast fc = forecast(ctx, p.getFarmlandId(), p.getFruitType(), p.getFillType());
            if (fc.directCosts() != null && fc.directCosts() > 0) {
                BridgeDtos.Field field = ctx.fields().get(p.getFarmlandId());
                // owner decision 2026-10-14: a window running now books into the first month of the plan (next month)
                long month = Math.max(anchor.monthIndex(w.start()), anchor.monthIndex(sg.getCurrentGameTime()) + 1);
                out.computeIfAbsent(month, k -> new ArrayList<>()).add(new PlannedCost(
                        (field == null ? String.valueOf(p.getFarmlandId()) : field.name()) + " · " + p.getFruitType(),
                        -Math.round(fc.directCosts())));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ A5 window, tasks

    /**
     * The sowing window of a plan from now on: the run of plantable periods that contains the current period (or the
     * chosen month, when it is in that run), else the next run; with a chosen month the window starts there. A chosen
     * month outside the windows is a window of one month. null without the windows of the crop.
     */
    Window window(Savegame sg, CropPlan p, CropOption crop) {
        if (crop == null || crop.plantablePeriods() == null || crop.plantablePeriods().isEmpty()) {
            return null;
        }
        List<Integer> plantable = crop.plantablePeriods();
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long m = anchor.monthIndex(sg.getCurrentGameTime());
        Integer chosen = p.getSowPeriod();
        java.util.function.LongPredicate in = idx -> plantable.contains(anchor.periodOf(idx));
        long start = -1;
        if (chosen != null && !plantable.contains(chosen)) {
            for (int k = 0; k < GameTime.PERIODS_PER_YEAR; k++) {
                if (anchor.periodOf(m + k) == chosen) {
                    start = m + k;
                    break;
                }
            }
            return new Window(anchor.monthStart(start), anchor.monthStart(start + 1), start == m);
        }
        if (in.test(m)) {
            start = m;
            if (chosen != null) {
                // the chosen month earlier in the running window counts from there, a later one starts the window there
                long back = m;
                while (back > m - GameTime.PERIODS_PER_YEAR && in.test(back) && anchor.periodOf(back) != chosen) {
                    back--;
                }
                if (!(in.test(back) && anchor.periodOf(back) == chosen)) {
                    start = -1;
                }
            }
        }
        if (start < 0) {
            for (int k = 1; k <= GameTime.PERIODS_PER_YEAR; k++) {
                if (in.test(m + k) && (chosen == null ? !in.test(m + k - 1) || k == 1 : anchor.periodOf(m + k) == chosen)) {
                    start = m + k;
                    break;
                }
            }
        }
        if (start < 0) {
            return null;
        }
        long end = start + 1;
        while (end < start + GameTime.PERIODS_PER_YEAR && in.test(end)) {
            end++;
        }
        return new Window(anchor.monthStart(start), anchor.monthStart(end), start <= m);
    }

    /** A5: open plans with a crop whose sowing window is now and whose season is not sown yet (tasks, calendar). */
    @Transactional(readOnly = true)
    public List<SowDue> due(Savegame sg) {
        List<CropPlan> open = plans.findBySavegameAndStatus(sg, Status.OPEN);
        if (open.isEmpty()) {
            return List.of();
        }
        Map<String, CropOption> crops = crops(sg);
        Map<Integer, String> names = new HashMap<>();
        facts.latest(sg).map(FarmFacts::fields).ifPresent(list -> list.stream()
                .filter(x -> x != null && x.farmlandId() != null).forEach(x -> names.put(x.farmlandId(), x.name())));
        List<SowDue> out = new ArrayList<>();
        for (CropPlan p : open) {
            if (p.getFruitType() == null) {
                continue;
            }
            Window w = window(sg, p, crops.get(p.getFruitType()));
            if (w != null) {
                out.add(new SowDue(p.getFarmlandId(), names.getOrDefault(p.getFarmlandId(), String.valueOf(p.getFarmlandId())),
                        p.getFruitType(), p.getFillType(), w.start(), w.end()));
            }
        }
        out.sort(Comparator.comparingLong(SowDue::start));
        return out;
    }

    /**
     * A5 (owner decision 2026-10-13, switch default on): at a month start the cooperative reminds of the planned fields
     * whose sowing window ends with this month and that are not sown yet - once per field and year.
     */
    @EventListener
    @Order(31)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!reminders(sg)) {
            return;
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        Integer year = f != null && f.calendar() != null ? f.calendar().year() : null;
        if (year == null) {
            return;
        }
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long m = anchor.monthIndex(sg.getCurrentGameTime());
        Map<String, CropOption> crops = crops(sg);
        List<String> names = new ArrayList<>();
        Map<Integer, String> fieldNames = new HashMap<>();
        if (f.fields() != null) {
            f.fields().stream().filter(x -> x != null && x.farmlandId() != null).forEach(x -> fieldNames.put(x.farmlandId(), x.name()));
        }
        for (CropPlan p : plans.findBySavegameAndStatus(sg, Status.OPEN)) {
            if (p.getFruitType() == null || Objects.equals(p.getRemindedYear(), year)) {
                continue;
            }
            Window w = window(sg, p, crops.get(p.getFruitType()));
            if (w != null && w.active() && anchor.monthIndex(w.end() - 1) == m) {
                names.add(fieldNames.getOrDefault(p.getFarmlandId(), String.valueOf(p.getFarmlandId())) + " ("
                        + p.getFruitType() + ")");
                p.setRemindedYear(year);
                plans.save(p);
            }
        }
        var cooperative = lookup.mandatory(sg, CharacterRole.COOPERATIVE);
        if (names.isEmpty() || cooperative.isEmpty()) {
            return;
        }
        narration.request(sg, NarrationEventType.CROP_PLAN_REMINDER).from(cooperative.get())
                .facts(NarrationFacts.builder().put("fields", String.join(", ", names)).put("count", names.size()).build())
                .category(CommunicationCategory.CONTRACT).submit();
    }

    // ------------------------------------------------------------------------------------------ A6 plan and reality

    private List<PlanActual> history(Context ctx, Savegame sg) {
        Map<Long, FieldBookEntry> byId = new HashMap<>();
        ctx.book().forEach(x -> byId.put(x.getId(), x));
        Map<Long, Double> actualMargins = new HashMap<>();
        margins.margins(sg).fields().forEach(fm -> fm.entries().forEach(em -> {
            if (em.totals() != null && em.totals().margin() != null) {
                actualMargins.put(em.entryId(), em.totals().margin());
            }
        }));
        List<PlanActual> out = new ArrayList<>();
        for (CropPlan p : plans.findBySavegameOrderByIdAsc(sg)) {
            if (p.getStatus() == Status.OPEN || p.getEntryId() == null) {
                continue;
            }
            FieldBookEntry e = byId.get(p.getEntryId());
            BridgeDtos.Field field = ctx.fields().get(p.getFarmlandId());
            String name = field != null ? field.name() : e != null && e.getFieldName() != null ? e.getFieldName()
                    : String.valueOf(p.getFarmlandId());
            boolean ended = e != null && e.getStatus() != FieldBookEntry.Status.RUNNING;
            out.add(new PlanActual(p.getId(), p.getFarmlandId(), name, p.getStatus().name(),
                    p.getFruitType() == null ? FALLOW : p.getFruitType(), e == null ? null : e.fruitType(),
                    e == null ? null : e.getHarvestYear(), ended, p.getPlannedLitersPerHa(),
                    ended ? FieldBookEvaluationService.perHectare(e) : null, p.getPlannedMargin(),
                    ended ? actualMargins.get(p.getEntryId()) : null));
        }
        out.sort(Comparator.comparing(PlanActual::id).reversed());
        return out;
    }

    /**
     * A6 (owner decision 2026-10-13): once per plan year the player sends the plan to the cooperative; the answer comes
     * the next game day and names the checks and the coverage - all figures from here, the AI only words them.
     */
    @Transactional
    public void discuss(Savegame sg) {
        Planning p = planning(sg);
        if (p.year() == null) {
            throw new BusinessRuleException("CROP_PLAN_CALENDAR", "Der Plan lässt sich besprechen, sobald der Mod den Kalender meldet.");
        }
        if (p.discussed()) {
            throw new BusinessRuleException("CROP_PLAN_DISCUSSED", "Der Plan wurde in diesem Jahr schon besprochen.");
        }
        List<FieldPlan> planned = p.fields().stream().filter(x -> x.plan() != null).toList();
        if (planned.isEmpty()) {
            throw new BusinessRuleException("CROP_PLAN_EMPTY", "Es gibt noch keinen Plan zum Besprechen.");
        }
        var cooperative = lookup.mandatory(sg, CharacterRole.COOPERATIVE)
                .orElseThrow(() -> new BusinessRuleException("CROP_PLAN_NO_COOPERATIVE", "Die Genossenschaft ist nicht erreichbar."));
        java.util.function.Function<String, Long> count = code -> planned.stream()
                .filter(x -> x.plan().checks().stream().anyMatch(c -> c.code().equals(code))).count();
        List<String> storage = planned.stream().flatMap(x -> x.plan().checks().stream())
                .filter(c -> c.code().equals("STORAGE")).map(c -> String.valueOf(c.values().get("fillType"))).distinct().toList();
        double expected = p.marketing().stream().mapToDouble(MarketNeed::expected).sum();
        double sold = p.marketing().stream().mapToDouble(m -> Math.min(m.expected(), m.contracted())).sum();
        Double feedPercent = p.feed().stream().map(FeedNeed::coveredPercent).filter(Objects::nonNull)
                .min(Double::compare).orElse(null);
        var facts = NarrationFacts.builder().put("plans", planned.size())
                .put("margin", p.planMargin() == null ? null : Math.round(p.planMargin()))
                .put("rotationFields", count.apply("ROTATION_SAME"))
                .put("windowFields", count.apply("SOW_OUTSIDE_WINDOW") + count.apply("TOO_LATE"))
                .put("limeFields", count.apply("LIME"))
                .put("storageFillTypes", storage.isEmpty() ? null : String.join(", ", storage))
                .put("soldPercent", expected > 0 ? Math.round(sold / expected * 100) : null)
                .put("feedPercent", feedPercent == null ? null : Math.round(Math.min(100, feedPercent)))
                .build();
        long now = sg.getCurrentGameTime();
        long answer = (GameTime.dayIndex(now) + 1) * GameTime.MS_PER_DAY + GameTime.hours(cfg().getDiscussAnswerHour());
        narration.request(sg, NarrationEventType.CROP_PLAN_DISCUSSION).from(cooperative).facts(facts)
                .category(CommunicationCategory.CONTRACT).delay(answer - now).submit();
        sg.setCropPlanDiscussedYear(p.year());
    }

    // ------------------------------------------------------------------------------------------ D5 / F5

    /**
     * Roadmap V4 R4-D5 (owner decision 2026-10-14): the expected revenue of every running season that has a plan - the
     * plan's forecast while nothing is sown or when the crop is the planned one (the values kept at sowing), the A3
     * forecast of the crop that stands when it deviates. Fields without a plan for the running season are missing.
     */
    @Transactional(readOnly = true)
    public Map<Integer, ExpectedRevenue> runningRevenue(Savegame sg) {
        Map<Integer, ExpectedRevenue> out = new HashMap<>();
        List<CropPlan> all = plans.findBySavegameOrderByIdAsc(sg);
        if (all.isEmpty()) {
            return out;
        }
        Context ctx = context(sg, false);
        for (FieldBookEntry running : ctx.book()) {
            if (running.getStatus() != FieldBookEntry.Status.RUNNING) {
                continue;
            }
            int id = running.getFarmlandId();
            if (running.fruitType() == null) {
                all.stream().filter(p -> p.getStatus() == Status.OPEN && p.getFarmlandId() == id
                        && running.getId() >= firstEntry(p)).findFirst().ifPresent(p -> {
                    Forecast fc = forecast(ctx, id, p.getFruitType(), p.getFillType());
                    out.put(id, new ExpectedRevenue("PLAN", fc.fruitType(), fc.fillType(), fc.hectares(), fc.litersPerHa(),
                            fc.yieldSource(), fc.pricePerLiter(), fc.priceSource(), fc.revenue()));
                });
                continue;
            }
            all.stream().filter(p -> Objects.equals(p.getEntryId(), running.getId())).findFirst().ifPresent(p -> {
                if (p.getStatus() == Status.MATCHED) {
                    Double revenue = p.getPlannedLitersPerHa() != null && p.getPlannedHectares() != null
                            && p.getPlannedPrice() != null
                            ? p.getPlannedLitersPerHa() * p.getPlannedHectares() * p.getPlannedPrice() : null;
                    out.put(id, new ExpectedRevenue("PLAN", p.getFruitType(), p.getFillType(), p.getPlannedHectares(),
                            p.getPlannedLitersPerHa(), p.getPlannedYieldSource(), p.getPlannedPrice(), null, revenue));
                } else {
                    Forecast fc = forecast(ctx, id, running.fruitType(), running.fillType());
                    out.put(id, new ExpectedRevenue("ACTUAL", fc.fruitType(), fc.fillType(), fc.hectares(), fc.litersPerHa(),
                            fc.yieldSource(), fc.pricePerLiter(), fc.priceSource(), fc.revenue()));
                }
            });
        }
        return out;
    }

    /** First field book entry a plan can be for (the running one it was made for, or a later one). */
    private static long firstEntry(CropPlan p) {
        return p.getTargetEntryId() != null ? p.getTargetEntryId() : p.getAfterEntryId() != null ? p.getAfterEntryId() + 1 : 0;
    }

    /**
     * Roadmap V4 R4-F5: the planned harvest as feed fill types (chaff counts as silage, crop-planning.feed-equivalents) -
     * open plans and MATCHED plans whose season still runs (owner decision 2026-10-14).
     */
    @Transactional(readOnly = true)
    public Map<String, Double> plannedFeedHarvest(Savegame sg) {
        Map<String, Double> out = new HashMap<>();
        if (plans.findBySavegameOrderByIdAsc(sg).isEmpty()) {
            return out;
        }
        Context ctx = context(sg, false);
        for (Harvest h : harvests(ctx, Map.of())) {
            out.merge(cfg().getFeedEquivalents().getOrDefault(h.fillType(), h.fillType()), h.liters(), Double::sum);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ A1 suggestion

    /**
     * A1 "Fruchtfolge vorschlagen": a crop that did not stand on the field in the last {@code rotation-years} seasons,
     * preferably the one with the best average margin per ha of D; without D the highest base yield x price.
     */
    @Transactional(readOnly = true)
    public Optional<Suggestion> suggest(Savegame sg, int farmlandId) {
        Context ctx = context(sg);
        List<String> recent = ctx.book().stream().filter(x -> x.getFarmlandId() == farmlandId && x.fruitType() != null)
                .map(FieldBookEntry::fruitType).toList();
        Set<String> blocked = new java.util.HashSet<>(recent.subList(Math.max(0, recent.size() - cfg().getRotationYears()),
                recent.size()));
        Suggestion best = null;
        for (FieldMarginService.CropMargin m : ctx.cropMargins()) {
            if (m.fruitType() != null && !blocked.contains(m.fruitType()) && ctx.crops().containsKey(m.fruitType())
                    && m.marginPerHectare() != null && (best == null || m.marginPerHectare() > best.value())) {
                best = new Suggestion(m.fruitType(), m.fillType(), "MARGIN", m.marginPerHectare());
            }
        }
        if (best != null) {
            return Optional.of(best);
        }
        for (CropOption c : ctx.crops().values()) {
            if (blocked.contains(c.name()) || c.litersPerSqm() == null || c.fillType() == null) {
                continue;
            }
            Double price = ctx.bestPrices().get(c.fillType());
            if (price == null) {
                continue;
            }
            double value = c.litersPerSqm() * 10_000 * cfg().getBaseYieldFactor() * price;
            if (best == null || value > best.value()) {
                best = new Suggestion(c.name(), c.fillType(), "YIELD", value);
            }
        }
        return Optional.ofNullable(best);
    }
}
