package de.farmpulse.rpsim.feed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.FeedAlert;
import de.farmpulse.rpsim.domain.FeedCounter;
import de.farmpulse.rpsim.domain.FeedLevel;
import de.farmpulse.rpsim.domain.FeedManualStock;
import de.farmpulse.rpsim.domain.FeedSnapshot;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.FeedAlertRepository;
import de.farmpulse.rpsim.repository.FeedCounterRepository;
import de.farmpulse.rpsim.repository.FeedLevelRepository;
import de.farmpulse.rpsim.repository.FeedManualStockRepository;
import de.farmpulse.rpsim.repository.FeedSnapshotRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap V4 section F: the feed balance (owner decisions 2026-10-13). Per stable the daily need - measured from the
 * eaten litres of the last {@code measure-days} game days and scaled when the herd changes, else the need of the game x
 * 24 ("geschätzt") - split into the food groups of the animal type (measured shares, else by productionWeight). Per
 * animal type and group the stock (own silos, bunker silos, the troughs and what the player entered), the reach in days
 * and game months with the traffic light, the date it runs empty, the shortfall until the next harvest, the yearly need
 * and the monthly costs. The animal keeper (without one the cooperative) warns when a group turns red, at most once per
 * game month and group. Stables of one animal type share the stock; a fill type several groups eat is shared by their
 * need.
 */
@Service
public class FeedBalanceService {

    /** Kind of a group: one of the food groups of the game, or the bedding (straw) of the stables. */
    public static final String FOOD = "FOOD";
    public static final String BEDDING = "BEDDING";
    public static final String STRAW = "STRAW";

    public static final String GREEN = "GREEN";
    public static final String YELLOW = "YELLOW";
    public static final String RED = "RED";
    /** The animals do not eat from the group (measured need 0) or its fill types are unknown. */
    public static final String UNUSED = "UNUSED";

    private static final TypeReference<Map<String, Double>> TOTALS = new TypeReference<>() {
    };

    /** Need of one fill type per day in a stable (measured). */
    public record FillTypeNeed(String fillType, double perDay) {
    }

    /** One stable: need per day, trough and straw, monthly costs. {@code troughHours} = until the trough is empty. */
    public record StableFeed(String husbandryUniqueId, String animalType, int count, boolean measured, double needPerDay,
                             Double troughLiters, Double troughCapacity, Double troughHours, Double strawPerDay,
                             Double straw, Double monthlyCost, List<FillTypeNeed> needs) {
    }

    /**
     * One food group (or the bedding) of an animal type. {@code stock} = the share of the silo stock and of the entered
     * stock ({@code manual}), {@code trough} = troughs (straw in the stables for the bedding); reach in days and game
     * months, {@code emptyAt} game time; {@code shortfall} = litres missing until {@code nextHarvestAt} (null = no
     * harvest known); the yearly need with coverage; {@code neighborTrade} = a fill type of the group fits an own silo.
     */
    public record GroupBalance(String title, String kind, Double productionWeight, List<String> fillTypes,
                               double needPerDay, boolean estimated, double stock, double manual, double trough,
                               Double reachDays, Double reachMonths, Long emptyAt, String status, Long nextHarvestAt,
                               Double shortfall, double yearNeed, Double coveredPercent, double yearBalance,
                               Double monthlyCost, boolean neighborTrade, Double plannedHarvest,
                               Double coveredWithPlanPercent, Double yearBalanceWithPlan, String nextHarvestSource) {
    }

    /**
     * Roadmap V4 R4-F3 / R4-F5: the crop plan of A as feed - planned litres per feed fill type and the start of their
     * harvest windows (game time).
     */
    public record Plan(Map<String, Double> harvest, Map<String, Long> harvestStart) {
    }

    public record AnimalBalance(String animalType, int animals, List<GroupBalance> groups) {
    }

    /** Stock the player entered: litres left, the bales as entered and how many are left of the chosen size. */
    public record ManualView(long id, String fillType, double liters, Integer bales, String baleSize, Double balesLeft,
                             String note, long enteredGameTime, long checkedGameTime, double ageDays, boolean stale) {
    }

    /** The tightest yellow or red group (home tile), null when every group is green. */
    public record Worst(String animalType, String title, String kind, String status, Double reachDays) {
    }

    /**
     * The whole balance. {@code tracked} = the mod reports the food values (otherwise the tab only asks to update the
     * mod); {@code counted} = the mod counts the eaten feed (otherwise the sinking troughs are measured).
     */
    public record Balance(boolean tracked, boolean counted, int measureDays, double okMonths, double warnMonths,
                          int daysPerPeriod, List<AnimalBalance> animals, List<StableFeed> stables,
                          List<ManualView> manual, Map<String, Double> baleSizes, Worst worst, Double monthlyCost) {
    }

    /** A yellow or red group with its date (tasks, calendar). */
    public record Due(String animalType, String title, String kind, String status, Double reachDays, Long emptyAt,
                      List<String> fillTypes) {
    }

    private final SavegameRepository savegames;
    private final FactsService facts;
    private final FeedCounterRepository counters;
    private final FeedSnapshotRepository snapshots;
    private final FeedLevelRepository levels;
    private final FeedManualStockRepository manual;
    private final FeedAlertRepository alerts;
    private final EmployeeRepository employees;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final GameTime gameTime;
    private final RpsimProperties props;
    private final JsonMapper json;

    public FeedBalanceService(SavegameRepository savegames, FactsService facts, FeedCounterRepository counters,
                              FeedSnapshotRepository snapshots, FeedLevelRepository levels,
                              FeedManualStockRepository manual, FeedAlertRepository alerts, EmployeeRepository employees,
                              CharacterLookup lookup, NarrationRequestService narration, GameTime gameTime,
                              RpsimProperties props, JsonMapper json) {
        this.savegames = savegames;
        this.facts = facts;
        this.counters = counters;
        this.snapshots = snapshots;
        this.levels = levels;
        this.manual = manual;
        this.alerts = alerts;
        this.employees = employees;
        this.lookup = lookup;
        this.narration = narration;
        this.gameTime = gameTime;
        this.props = props;
        this.json = json;
    }

    private RpsimProperties.FeedBalance cfg() {
        return props.getFormulas().getFeedBalance();
    }

    // ------------------------------------------------------------------------------------------ F1 / F2 exports

    /**
     * Every export: the eaten litres per stable and fill type (the difference of the mod counter, a falling counter is
     * taken back; without the counter the fall of the trough), the first snapshot of a new stable, and the bales: what a
     * trough got that the silos did not give came from entered stock and lowers it.
     */
    @EventListener
    @Order(8)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.husbandries() == null) {
            return;
        }
        Map<String, FeedCounter> known = new HashMap<>();
        counters.findBySavegame(sg).forEach(c -> known.put(c.getHusbandryUniqueId() + "|" + c.getFillType(), c));
        Map<String, FeedLevel> seen = new HashMap<>();
        levels.findBySavegame(sg).forEach(l -> seen.put(l.getRef(), l));
        Map<String, Double> refill = new HashMap<>();
        for (BridgeDtos.Husbandry h : f.husbandries()) {
            if (h == null || h.husbandryUniqueId() == null) {
                continue;
            }
            String id = h.husbandryUniqueId();
            Map<String, Double> eaten = new HashMap<>();
            if (h.feedConsumed() != null) {
                for (BridgeDtos.FeedCounter c : h.feedConsumed()) {
                    if (c == null || c.fillType() == null || c.liters() == null) {
                        continue;
                    }
                    FeedCounter row = counter(sg, known, id, c.fillType());
                    if (row.getLastCounter() != null) {
                        double delta = c.liters() - row.getLastCounter();
                        row.setTotal(Math.max(0, row.getTotal() + delta));
                        if (delta > 0) {
                            eaten.merge(c.fillType(), delta, Double::sum);
                        }
                    }
                    row.setLastCounter(c.liters()); // the first value is the base: only what is eaten from now counts
                    counters.save(row);
                }
            }
            if (h.trough() != null) {
                Map<String, Double> trough = amounts(h.trough());
                Set<String> fillTypes = new LinkedHashSet<>(trough.keySet());
                String prefix = "T|" + id + "|";
                seen.keySet().stream().filter(r -> r.startsWith(prefix)).forEach(r -> fillTypes.add(r.substring(prefix.length())));
                for (String ft : fillTypes) {
                    double now = trough.getOrDefault(ft, 0.0);
                    Double before = level(sg, seen, prefix + ft, now);
                    if (before == null) {
                        continue;
                    }
                    if (h.feedConsumed() == null && before > now) {
                        FeedCounter row = counter(sg, known, id, ft); // older mod: the sinking trough is what was eaten
                        row.setTotal(row.getTotal() + before - now);
                        row.setLastTrough(now);
                        counters.save(row);
                        eaten.merge(ft, before - now, Double::sum);
                    }
                    double got = now - before + eaten.getOrDefault(ft, 0.0);
                    if (got > 0) {
                        refill.merge(ft, got, Double::sum);
                    }
                }
            }
            if (h.straw() != null) {
                Double before = level(sg, seen, "S|" + id, h.straw());
                if (before != null && h.straw() > before) {
                    refill.merge(STRAW, h.straw() - before, Double::sum);
                }
            }
            if ((h.foodLitersPerHour() != null || h.feedConsumed() != null || h.trough() != null)
                    && !snapshots.existsBySavegameAndHusbandryUniqueId(sg, id)) {
                snapshot(sg, h, e.gameTime(), known);
            }
        }
        if (f.feedStock() != null) {
            Map<String, Double> stock = amounts(f.feedStock());
            Set<String> fillTypes = new LinkedHashSet<>(stock.keySet());
            seen.keySet().stream().filter(r -> r.startsWith("K|")).forEach(r -> fillTypes.add(r.substring(2)));
            Map<String, Double> siloFall = new HashMap<>();
            for (String ft : fillTypes) {
                double now = stock.getOrDefault(ft, 0.0);
                Double before = level(sg, seen, "K|" + ft, now);
                if (before != null && before > now) {
                    siloFall.put(ft, before - now);
                }
            }
            refill.forEach((ft, got) -> takeFromManual(sg, ft, got - siloFall.getOrDefault(ft, 0.0)));
        }
    }

    private static FeedCounter counter(Savegame sg, Map<String, FeedCounter> known, String id, String fillType) {
        return known.computeIfAbsent(id + "|" + fillType, k -> {
            FeedCounter c = new FeedCounter();
            c.setSavegame(sg);
            c.setHusbandryUniqueId(id);
            c.setFillType(fillType);
            c.setTotal(0);
            return c;
        });
    }

    /** Stores the new level and returns the one before (null = first seen). */
    private Double level(Savegame sg, Map<String, FeedLevel> seen, String ref, double now) {
        FeedLevel l = seen.get(ref);
        Double before = l == null ? null : l.getAmount();
        if (l == null) {
            l = new FeedLevel();
            l.setSavegame(sg);
            l.setRef(ref);
            seen.put(ref, l);
        }
        if (before == null || before != now) {
            l.setAmount(now);
            levels.save(l);
        }
        return before;
    }

    /** F2: litres a trough got from bales lower the entered stock of the fill type, the oldest entry first. */
    private void takeFromManual(Savegame sg, String fillType, double liters) {
        if (liters <= 0) {
            return;
        }
        double left = liters;
        for (FeedManualStock m : manual.findBySavegameOrderByIdAsc(sg)) {
            if (left <= 0) {
                break;
            }
            if (fillType.equals(m.getFillType()) && m.getLiters() > 0) {
                double take = Math.min(left, m.getLiters());
                m.setLiters(m.getLiters() - take);
                manual.save(m);
                left -= take;
            }
        }
    }

    private void snapshot(Savegame sg, BridgeDtos.Husbandry h, long now, Map<String, FeedCounter> known) {
        Map<String, Double> totals = new TreeMap<>();
        known.values().stream().filter(c -> h.husbandryUniqueId().equals(c.getHusbandryUniqueId()))
                .forEach(c -> totals.put(c.getFillType(), c.getTotal()));
        FeedSnapshot s = new FeedSnapshot();
        s.setSavegame(sg);
        s.setHusbandryUniqueId(h.husbandryUniqueId());
        s.setGameTime(now);
        s.setLitersPerHour(h.foodLitersPerHour());
        s.setTotalsJson(json.writeValueAsString(totals));
        snapshots.save(s);
    }

    /** F1 / F4 daily: a snapshot per stable (older ones than the measuring period go) and the warnings. */
    @EventListener
    @Order(73)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.husbandries() == null) {
            return;
        }
        Map<String, FeedCounter> known = new HashMap<>();
        counters.findBySavegame(sg).forEach(c -> known.put(c.getHusbandryUniqueId() + "|" + c.getFillType(), c));
        for (BridgeDtos.Husbandry h : f.husbandries()) {
            if (h != null && h.husbandryUniqueId() != null
                    && (h.foodLitersPerHour() != null || h.feedConsumed() != null || h.trough() != null)) {
                snapshot(sg, h, e.gameTime(), known);
            }
        }
        long keep = e.gameTime() - GameTime.days(cfg().getMeasureDays() + 1);
        snapshots.deleteAll(snapshots.findBySavegameOrderByGameTimeAsc(sg).stream()
                .filter(s -> s.getGameTime() < keep).toList());
        warn(sg);
    }

    /** A rewind: snapshots after the loaded time go, the levels start again, a mail of the lost time may come again. */
    @EventListener
    @Transactional
    public void onRewound(BridgeEvents.Rewound e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        snapshots.deleteAll(snapshots.findBySavegameOrderByGameTimeAsc(sg).stream()
                .filter(s -> s.getGameTime() > e.rewoundToGameTime()).toList());
        levels.deleteAll(levels.findBySavegame(sg));
        for (FeedAlert a : alerts.findBySavegame(sg)) {
            if (a.getLastMailGameTime() != null && a.getLastMailGameTime() > e.rewoundToGameTime()) {
                a.setLastMailGameTime(null);
                alerts.save(a);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ F4 warnings

    /**
     * F4 (owner decision 2026-10-13): a red group gets a mail of the animal keeper (without one the cooperative) - at
     * most one per game month and group, so the first when it turns red and one more in every following month.
     */
    @Transactional
    public int warn(Savegame sg) {
        if (!cfg().isWarningsEnabled()) {
            return 0;
        }
        Balance b = balance(sg);
        if (!b.tracked()) {
            return 0;
        }
        long now = sg.getCurrentGameTime();
        long month = gameTime.monthIndex(sg, now);
        Map<String, FeedAlert> known = new HashMap<>();
        alerts.findBySavegame(sg).forEach(a -> known.put(a.getAnimalType() + "|" + a.getGroupTitle(), a));
        int sent = 0;
        for (AnimalBalance a : b.animals()) {
            for (GroupBalance g : a.groups()) {
                if (!RED.equals(g.status())) {
                    continue;
                }
                FeedAlert alert = known.get(a.animalType() + "|" + g.title());
                if (alert != null && alert.getLastMailGameTime() != null
                        && gameTime.monthIndex(sg, alert.getLastMailGameTime()) >= month) {
                    continue;
                }
                if (!mail(sg, a.animalType(), g)) {
                    continue;
                }
                if (alert == null) {
                    alert = new FeedAlert();
                    alert.setSavegame(sg);
                    alert.setAnimalType(a.animalType());
                    alert.setGroupTitle(g.title());
                }
                alert.setLastMailGameTime(now);
                alerts.save(alert);
                sent++;
            }
        }
        return sent;
    }

    private boolean mail(Savegame sg, String animalType, GroupBalance g) {
        var keeper = employees.findBySavegameAndStatusAndJobRole(sg, EmployeeStatus.ACTIVE, JobRole.ANIMAL_KEEPER).stream()
                .filter(k -> k.getStrikeSinceGameTime() == null).findFirst().orElse(null);
        var facts = NarrationFacts.builder().put("animalType", animalType)
                .put("groupTitle", BEDDING.equals(g.kind()) ? "Einstreu" : g.title())
                .put("reachDays", round1(g.reachDays())).put("reachMonths", round1(g.reachMonths()))
                .put("needPerDay", Math.round(g.needPerDay()))
                .put("shortfall", g.shortfall() == null || g.shortfall() <= 0 ? null : Math.round(g.shortfall()))
                .build();
        if (keeper != null) {
            narration.request(sg, NarrationEventType.FEED_SHORTAGE_WARNING).from(keeper.getCharacter()).facts(facts)
                    .category(CommunicationCategory.EMPLOYEE).related("EMPLOYEE", keeper.getId()).submit();
            return true;
        }
        var cooperative = lookup.mandatory(sg, CharacterRole.COOPERATIVE);
        if (cooperative.isEmpty()) {
            return false;
        }
        narration.request(sg, NarrationEventType.FEED_SHORTAGE_WARNING).from(cooperative.get()).facts(facts)
                .category(CommunicationCategory.LIVESTOCK).submit();
        return true;
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }

    /** F4: the yellow and red groups with the date they run empty (tasks and calendar). */
    @Transactional(readOnly = true)
    public List<Due> due(Savegame sg) {
        List<Due> out = new ArrayList<>();
        Balance b = balance(sg);
        if (!b.tracked()) {
            return out;
        }
        for (AnimalBalance a : b.animals()) {
            for (GroupBalance g : a.groups()) {
                if (RED.equals(g.status()) || YELLOW.equals(g.status())) {
                    out.add(new Due(a.animalType(), g.title(), g.kind(), g.status(), g.reachDays(), g.emptyAt(),
                            g.fillTypes()));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ F2 entered stock

    /** F2: bales of a size (litres from {@code bale-sizes}) or litres directly, with an optional note. */
    @Transactional
    public FeedManualStock addManual(Savegame sg, String fillType, Integer bales, String baleSize, Double liters,
                                     String note) {
        FeedManualStock m = new FeedManualStock();
        m.setSavegame(sg);
        m.setEnteredGameTime(sg.getCurrentGameTime());
        fill(m, fillType, bales, baleSize, liters, note, sg.getCurrentGameTime());
        return manual.save(m);
    }

    /** F2: a corrected or confirmed entry counts from now ("eingetragen vor 0 Tagen"). */
    @Transactional
    public FeedManualStock updateManual(Savegame sg, long id, String fillType, Integer bales, String baleSize,
                                        Double liters, String note) {
        FeedManualStock m = ownManual(sg, id);
        fill(m, fillType, bales, baleSize, liters, note, sg.getCurrentGameTime());
        return manual.save(m);
    }

    /** F2: "stimmt noch" - the entry stays as it is and its reminder starts again. */
    @Transactional
    public FeedManualStock checkManual(Savegame sg, long id) {
        FeedManualStock m = ownManual(sg, id);
        m.setCheckedGameTime(sg.getCurrentGameTime());
        return manual.save(m);
    }

    @Transactional
    public void removeManual(Savegame sg, long id) {
        manual.delete(ownManual(sg, id));
    }

    private void fill(FeedManualStock m, String fillType, Integer bales, String baleSize, Double liters, String note,
                      long now) {
        if (fillType == null || fillType.isBlank()) {
            throw new BusinessRuleException("FEED_FILL_TYPE", "Bitte eine Futtersorte wählen.");
        }
        double amount;
        if (bales != null && baleSize != null) {
            Double size = cfg().getBaleSizes().get(baleSize);
            if (size == null) {
                throw new BusinessRuleException("FEED_BALE_SIZE", "Diese Ballengröße gibt es nicht.");
            }
            if (bales < 0) {
                throw new BusinessRuleException("FEED_NUMBER", "Bitte eine Zahl ab 0 eintragen.");
            }
            amount = bales * size;
        } else {
            if (liters == null || liters.isNaN() || liters < 0) {
                throw new BusinessRuleException("FEED_NUMBER", "Bitte eine Zahl ab 0 eintragen.");
            }
            amount = liters;
            bales = null;
            baleSize = null;
        }
        m.setFillType(fillType.trim());
        m.setBales(bales);
        m.setBaleSize(baleSize);
        m.setLiters(amount);
        m.setNote(note == null || note.isBlank() ? null : note.trim());
        m.setCheckedGameTime(now);
    }

    private FeedManualStock ownManual(Savegame sg, long id) {
        return manual.findById(id).filter(m -> m.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("Eintrag " + id + " nicht gefunden"));
    }

    // ------------------------------------------------------------------------------------------ F1 / F3 / F5 balance

    /** Food group of an animal type as the game defines it, or the bedding. */
    private record Group(String title, String kind, Double weight, List<String> fillTypes) {
    }

    /** Working values of one stable. */
    private record Stable(BridgeDtos.Husbandry h, String type, int count, boolean measured, double needPerDay,
                          Map<String, Double> rates, Map<String, Double> trough) {
    }

    /** Working values of one group of an animal type. */
    private static final class Acc {
        final String type;
        final Group group;
        double need;
        boolean estimated;
        double trough;
        double cost;
        boolean costKnown;

        Acc(String type, Group group) {
            this.type = type;
            this.group = group;
        }
    }

    @Transactional(readOnly = true)
    public Balance balance(Savegame sg) {
        return balance(sg, (Plan) null);
    }

    /** F5 compatibility: planned litres only. */
    public Balance balance(Savegame sg, Map<String, Double> planned) {
        return balance(sg, planned == null ? null : new Plan(planned, Map.of()));
    }

    /**
     * F5: with {@code planned} (litres per feed fill type of the crop plan, A4) the yearly need is also compared with
     * stock + planned harvest; null = without the plan (warnings, tasks, the planner itself).
     */
    @Transactional(readOnly = true)
    public Balance balance(Savegame sg, Plan plan) {
        Map<String, Double> planned = plan == null ? null : plan.harvest();
        long now = sg.getCurrentGameTime();
        int dpp = gameTime.anchor(sg).daysPerPeriod();
        FarmFacts f = facts.latest(sg).orElse(null);
        var market = facts.marketContext(sg).orElse(null);
        List<ManualView> manualViews = manualViews(sg, now);
        List<BridgeDtos.Husbandry> husbandries = f == null || f.husbandries() == null ? List.of()
                : f.husbandries().stream().filter(h -> h != null && h.husbandryUniqueId() != null).toList();
        boolean tracked = husbandries.stream().anyMatch(h -> h.foodLitersPerHour() != null);
        boolean counted = husbandries.stream().anyMatch(h -> h.feedConsumed() != null);
        if (!tracked) {
            return new Balance(false, counted, cfg().getMeasureDays(), cfg().getOkMonths(), cfg().getWarnMonths(), dpp,
                    List.of(), List.of(), manualViews, cfg().getBaleSizes(), null, null);
        }
        Map<String, Double> costs = new HashMap<>();
        if (market != null && market.fillTypeCosts() != null) {
            market.fillTypeCosts().stream().filter(c -> c != null && c.fillType() != null && c.costPerLiter() != null)
                    .forEach(c -> costs.put(c.fillType(), c.costPerLiter()));
        }
        Map<String, List<Group>> groupsByType = new HashMap<>();
        if (market != null && market.animalFood() != null) {
            for (BridgeDtos.AnimalFood a : market.animalFood()) {
                if (a == null || a.animalType() == null || a.groups() == null) {
                    continue;
                }
                List<Group> groups = new ArrayList<>();
                for (BridgeDtos.FoodGroup g : a.groups()) {
                    if (g != null && g.title() != null && g.fillTypes() != null && !g.fillTypes().isEmpty()) {
                        groups.add(new Group(g.title(), FOOD, g.productionWeight(), g.fillTypes()));
                    }
                }
                groupsByType.put(a.animalType(), groups);
            }
        }
        // animals per stable (assets.animals)
        Map<String, String> typeOf = new HashMap<>();
        Map<String, Integer> countOf = new HashMap<>();
        if (f.assets() != null && f.assets().animals() != null) {
            for (BridgeDtos.Animal a : f.assets().animals()) {
                if (a != null && a.husbandryUniqueId() != null && a.type() != null) {
                    typeOf.put(a.husbandryUniqueId(), a.type());
                    countOf.merge(a.husbandryUniqueId(), a.count() == null ? 0 : a.count(), Integer::sum);
                }
            }
        }
        Map<String, List<FeedCounter>> countersOf = new HashMap<>();
        counters.findBySavegame(sg).forEach(c -> countersOf.computeIfAbsent(c.getHusbandryUniqueId(), k -> new ArrayList<>())
                .add(c));
        Map<String, List<FeedSnapshot>> snapsOf = new HashMap<>();
        snapshots.findBySavegameOrderByGameTimeAsc(sg).forEach(s -> snapsOf.computeIfAbsent(s.getHusbandryUniqueId(),
                k -> new ArrayList<>()).add(s));

        List<Stable> stables = new ArrayList<>();
        for (BridgeDtos.Husbandry h : husbandries) {
            String type = typeOf.get(h.husbandryUniqueId());
            if (type == null || h.foodLitersPerHour() == null) {
                continue;
            }
            Map<String, Double> rates = measured(h, countersOf.getOrDefault(h.husbandryUniqueId(), List.of()),
                    snapsOf.getOrDefault(h.husbandryUniqueId(), List.of()), now);
            boolean measured = rates != null;
            double need = measured ? rates.values().stream().mapToDouble(Double::doubleValue).sum()
                    : h.foodLitersPerHour() * 24;
            stables.add(new Stable(h, type, countOf.getOrDefault(h.husbandryUniqueId(), 0), measured, need,
                    measured ? rates : Map.of(), h.trough() == null ? Map.of() : amounts(h.trough())));
        }

        // groups per animal type (order of the game, then the bedding)
        Map<String, List<Acc>> byType = new TreeMap<>();
        Map<String, Double> stableCost = new HashMap<>();
        for (Stable s : stables) {
            List<Group> groups = groupsByType.get(s.type());
            if (groups == null) {
                Set<String> fts = new LinkedHashSet<>(s.trough().keySet());
                fts.addAll(s.rates().keySet());
                groups = List.of(new Group(s.type(), FOOD, 1.0, List.copyOf(fts)));
            }
            List<Acc> accs = byType.computeIfAbsent(s.type(), k -> new ArrayList<>());
            List<Group> all = new ArrayList<>(groups);
            if (s.h().strawLitersPerHour() != null) {
                all.add(new Group(STRAW, BEDDING, null, List.of(STRAW)));
            }
            double weights = groups.stream().mapToDouble(g -> g.weight() == null ? 0 : g.weight()).sum();
            Set<String> assigned = new java.util.HashSet<>();
            for (Group g : all) {
                Acc acc = accs.stream().filter(a -> a.group.title().equals(g.title()) && a.group.kind().equals(g.kind()))
                        .findFirst().orElse(null);
                if (acc == null) {
                    acc = new Acc(s.type(), g);
                    accs.add(acc);
                }
                double need;
                double cost = 0;
                boolean costKnown = false;
                if (BEDDING.equals(g.kind())) {
                    need = s.h().strawLitersPerHour() * 24;
                    acc.estimated = true;
                    acc.trough += s.h().straw() == null ? 0 : s.h().straw();
                    if (costs.containsKey(STRAW)) {
                        cost = need * costs.get(STRAW);
                        costKnown = true;
                    }
                } else {
                    List<String> own = g.fillTypes().stream().filter(assigned::add).toList(); // first group wins
                    if (s.measured()) {
                        need = own.stream().mapToDouble(ft -> s.rates().getOrDefault(ft, 0.0)).sum();
                        for (String ft : own) {
                            if (costs.containsKey(ft) && s.rates().containsKey(ft)) {
                                cost += s.rates().get(ft) * costs.get(ft);
                                costKnown = true;
                            }
                        }
                    } else {
                        double w = g.weight() == null ? 0 : g.weight();
                        need = s.needPerDay() * (weights > 0 ? w / weights : 1.0 / groups.size());
                        acc.estimated = acc.estimated || need > 0;
                        double[] prices = g.fillTypes().stream().filter(costs::containsKey).mapToDouble(costs::get).toArray();
                        if (prices.length > 0) {
                            cost = need * java.util.Arrays.stream(prices).average().orElse(0);
                            costKnown = true;
                        }
                    }
                    acc.trough += own.stream().mapToDouble(ft -> s.trough().getOrDefault(ft, 0.0)).sum();
                }
                acc.need += need;
                if (costKnown) {
                    acc.cost += cost * dpp;
                    acc.costKnown = true;
                    stableCost.merge(s.h().husbandryUniqueId(), cost * dpp, Double::sum);
                }
            }
        }

        // stock per fill type (silos + entered), shared by the need of every group that eats it
        Map<String, Double> auto = f.feedStock() == null ? Map.of() : amounts(f.feedStock());
        Map<String, Double> entered = new HashMap<>();
        manualViews.forEach(m -> entered.merge(m.fillType(), m.liters(), Double::sum));
        Map<String, Double> needOf = new HashMap<>();
        Map<String, Integer> eatersOf = new HashMap<>();
        byType.values().forEach(list -> list.forEach(acc -> acc.group.fillTypes().forEach(ft -> {
            needOf.merge(ft, acc.need, Double::sum);
            eatersOf.merge(ft, 1, Integer::sum);
        })));
        Set<String> tradeable = new java.util.HashSet<>();
        if (f.tradeStorage() != null) {
            f.tradeStorage().stream().filter(t -> t != null && t.fillType() != null).forEach(t -> tradeable.add(t.fillType()));
        }
        List<AnimalBalance> animals = new ArrayList<>();
        for (var entry : byType.entrySet()) {
            List<GroupBalance> groups = new ArrayList<>();
            for (Acc acc : entry.getValue()) {
                double stock = 0;
                double man = 0;
                double planLiters = 0;
                for (String ft : acc.group.fillTypes()) {
                    double all = needOf.getOrDefault(ft, 0.0);
                    double share = all > 0 ? acc.need / all : 1.0 / eatersOf.getOrDefault(ft, 1);
                    stock += (auto.getOrDefault(ft, 0.0) + entered.getOrDefault(ft, 0.0)) * share;
                    man += entered.getOrDefault(ft, 0.0) * share;
                    planLiters += (planned == null ? 0 : planned.getOrDefault(ft, 0.0)) * share;
                }
                groups.add(group(sg, acc, stock, man, now, dpp, market, tradeable, planned == null ? null : planLiters,
                        plan == null ? Map.of() : plan.harvestStart()));
            }
            int count = stables.stream().filter(s -> s.type().equals(entry.getKey())).mapToInt(Stable::count).sum();
            animals.add(new AnimalBalance(entry.getKey(), count, groups));
        }

        List<StableFeed> stableViews = stables.stream().map(s -> {
            double troughLiters = s.trough().values().stream().mapToDouble(Double::doubleValue).sum();
            Double hours = s.needPerDay() > 0 ? troughLiters / (s.needPerDay() / 24) : null;
            List<FillTypeNeed> needs = s.rates().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(x -> new FillTypeNeed(x.getKey(), x.getValue())).toList();
            return new StableFeed(s.h().husbandryUniqueId(), s.type(), s.count(), s.measured(), s.needPerDay(),
                    s.h().trough() == null ? null : troughLiters, s.h().foodCapacity(), hours,
                    s.h().strawLitersPerHour() == null ? null : s.h().strawLitersPerHour() * 24, s.h().straw(),
                    stableCost.get(s.h().husbandryUniqueId()), needs);
        }).toList();
        Worst worst = null;
        for (AnimalBalance a : animals) {
            for (GroupBalance g : a.groups()) {
                if ((RED.equals(g.status()) || YELLOW.equals(g.status())) && g.reachDays() != null
                        && (worst == null || g.reachDays() < worst.reachDays())) {
                    worst = new Worst(a.animalType(), g.title(), g.kind(), g.status(), g.reachDays());
                }
            }
        }
        Double total = stableCost.isEmpty() ? null : stableCost.values().stream().mapToDouble(Double::doubleValue).sum();
        return new Balance(true, counted, cfg().getMeasureDays(), cfg().getOkMonths(), cfg().getWarnMonths(), dpp,
                animals, stableViews, manualViews, cfg().getBaleSizes(), worst, total);
    }

    /**
     * F1: litres per day and fill type from the oldest snapshot of the last measure-days game days (at least one game
     * day ago), scaled by the need of the game now / its mean in the measuring period (the herd changed). null = not
     * measured (no such snapshot, or nothing eaten - an empty trough measures nothing).
     */
    private Map<String, Double> measured(BridgeDtos.Husbandry h, List<FeedCounter> rows, List<FeedSnapshot> snaps,
                                         long now) {
        long from = now - GameTime.days(cfg().getMeasureDays());
        FeedSnapshot base = snaps.stream().filter(s -> s.getGameTime() >= from).findFirst().orElse(null);
        if (base == null || now - base.getGameTime() < GameTime.MS_PER_DAY) {
            return null;
        }
        double days = GameTime.toDays(now - base.getGameTime());
        Map<String, Double> before = json.readValue(base.getTotalsJson(), TOTALS);
        Map<String, Double> rates = new TreeMap<>();
        for (FeedCounter c : rows) {
            double d = c.getTotal() - before.getOrDefault(c.getFillType(), 0.0);
            if (d > 0) {
                rates.put(c.getFillType(), d / days);
            }
        }
        if (rates.isEmpty()) {
            return null;
        }
        double[] lph = snaps.stream().filter(s -> s.getGameTime() >= base.getGameTime())
                .map(FeedSnapshot::getLitersPerHour).filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
        double mean = java.util.Arrays.stream(lph).average().orElse(0);
        if (h.foodLitersPerHour() != null && mean > 0) {
            double factor = h.foodLitersPerHour() / mean;
            rates.replaceAll((k, v) -> v * factor);
        }
        return rates;
    }

    private GroupBalance group(Savegame sg, Acc acc, double stock, double man, long now, int dpp,
                               BridgeDtos.MarketContext market, Set<String> tradeable, Double plannedHarvest,
                               Map<String, Long> planStart) {
        double total = stock + acc.trough;
        Double reach = acc.need > 0 && !acc.group.fillTypes().isEmpty() ? total / acc.need : null;
        Double months = reach == null ? null : reach / dpp;
        String status = months == null ? UNUSED
                : months >= cfg().getOkMonths() ? GREEN : months < cfg().getWarnMonths() ? RED : YELLOW;
        Long emptyAt = reach == null ? null : now + GameTime.days(reach);
        // F3: the next harvest from the plan of A (the harvest window of a planned crop), else from the crops' windows
        Long fromPlan = acc.group.fillTypes().stream().map(planStart::get).filter(Objects::nonNull)
                .map(t -> Math.max(t, now)).min(Long::compare).orElse(null);
        Long harvest = reach == null ? null : fromPlan != null ? fromPlan : nextHarvest(sg, acc.group.fillTypes(), market, now);
        String harvestSource = harvest == null ? null : fromPlan != null ? "PLAN" : "CROPS";
        Double shortfall = harvest == null ? null : Math.max(0, acc.need * GameTime.toDays(harvest - now) - total);
        double yearNeed = acc.need * GameTime.PERIODS_PER_YEAR * dpp;
        return new GroupBalance(acc.group.title(), acc.group.kind(), acc.group.weight(), acc.group.fillTypes(), acc.need,
                acc.estimated, stock, man, acc.trough, reach, months, emptyAt, status, harvest, shortfall, yearNeed,
                yearNeed > 0 ? total / yearNeed * 100 : null, total - yearNeed, acc.costKnown ? acc.cost : null,
                acc.group.fillTypes().stream().anyMatch(tradeable::contains), plannedHarvest,
                plannedHarvest == null || yearNeed <= 0 ? null : (total + plannedHarvest) / yearNeed * 100,
                plannedHarvest == null ? null : total + plannedHarvest - yearNeed, harvestSource);
    }

    /**
     * F3: start of the next game month in which a crop of the fill types ({@code harvest-crops}) can be harvested
     * (fruitTypes[].harvestablePeriods, Q1); now when it can be harvested this month. null = unknown.
     */
    private Long nextHarvest(Savegame sg, List<String> fillTypes, BridgeDtos.MarketContext market, long now) {
        if (market == null || market.fruitTypes() == null) {
            return null;
        }
        Set<Integer> periods = new java.util.HashSet<>();
        for (String ft : fillTypes) {
            for (String crop : cfg().getHarvestCrops().getOrDefault(ft, List.of())) {
                market.fruitTypes().stream().filter(x -> x != null && crop.equals(x.name()) && x.harvestablePeriods() != null)
                        .forEach(x -> periods.addAll(x.harvestablePeriods()));
            }
        }
        if (periods.isEmpty()) {
            return null;
        }
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long month = anchor.monthIndex(now);
        for (int k = 0; k < GameTime.PERIODS_PER_YEAR; k++) {
            if (periods.contains(anchor.periodOf(month + k))) {
                return k == 0 ? now : anchor.monthStart(month + k);
            }
        }
        return null;
    }

    private List<ManualView> manualViews(Savegame sg, long now) {
        return manual.findBySavegameOrderByIdAsc(sg).stream().map(m -> {
            Double size = m.getBaleSize() == null ? null : cfg().getBaleSizes().get(m.getBaleSize());
            double age = GameTime.toDays(now - m.getCheckedGameTime());
            return new ManualView(m.getId(), m.getFillType(), m.getLiters(), m.getBales(), m.getBaleSize(),
                    size == null || size <= 0 ? null : Math.round(m.getLiters() / size * 10) / 10.0, m.getNote(),
                    m.getEnteredGameTime(), m.getCheckedGameTime(), Math.max(0, age), age >= cfg().getManualStaleDays());
        }).toList();
    }

    private static Map<String, Double> amounts(List<BridgeDtos.FeedAmount> list) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (BridgeDtos.FeedAmount a : list) {
            if (a != null && a.fillType() != null && a.amount() != null) {
                out.merge(a.fillType(), a.amount(), Double::sum);
            }
        }
        return out;
    }
}
