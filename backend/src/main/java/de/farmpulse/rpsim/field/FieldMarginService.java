package de.farmpulse.rpsim.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
import de.farmpulse.rpsim.bridge.PriceQueryService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractBillingService;
import de.farmpulse.rpsim.domain.BookingEntry;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.EmployeeStatus;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.FieldBookEntry.Status;
import de.farmpulse.rpsim.domain.FieldInputCounter;
import de.farmpulse.rpsim.domain.FieldMarginEntry;
import de.farmpulse.rpsim.domain.FieldMarginItem;
import de.farmpulse.rpsim.domain.FieldMarginItem.Kind;
import de.farmpulse.rpsim.domain.FieldMarginItem.Source;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.farmwork.ContractorWorkService;
import de.farmpulse.rpsim.finance.FinanceJournalService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.BookingEntryRepository;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.EmployeeRepository;
import de.farmpulse.rpsim.repository.FieldBookEntryRepository;
import de.farmpulse.rpsim.repository.FieldInputCounterRepository;
import de.farmpulse.rpsim.repository.FieldMarginEntryRepository;
import de.farmpulse.rpsim.repository.FieldMarginItemRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap V4 section D: the contribution margin per field book entry (owner decisions 2026-10-13;
 * {@code docs/architecture/ROADMAP_V4.md}). The lines are booked into the field book entry that runs when they happen:
 * <ul>
 *   <li>D1 direct costs from the consumption counter of the mod ({@code farm_facts.inputs}), valued with the purchase
 *   price of the game at that moment ({@code market_context.fillTypeCosts}), litres a helper bought x the helper
 *   surcharge, own farmyard manure 0 euro; without the counter (older mod) an estimate per hectare from the field book
 *   ticks; contractor fees and rent from the acknowledged money instructions; area and rotation premium per field;</li>
 *   <li>D2 revenue = litres of the field book x the realised average price of the product (sales from the harvest to
 *   the end of the following FS25 year), else the best price on the harvest day, else purchase price x share;</li>
 *   <li>D3 overheads of the booking journal (and the salaries of the drivers) distributed by area at every month end;</li>
 *   <li>D4 corrections (they win and can be given back), own lines, closed years hold what comes late.</li>
 * </ul>
 * Pure fact layer: the AI never computes a number here.
 */
@Service
public class FieldMarginService {

    /** Kinds of the lines that come from the consumption counter (one line per entry and fill type). */
    private static final Set<Kind> INPUT_KINDS = Set.of(Kind.SEED, Kind.FERTILIZER, Kind.LIME, Kind.PROTECTION,
            Kind.MANURE, Kind.OTHER_INPUT);
    /** Kinds the estimate covers (older mod without the counter); an entered line of the same kind replaces it. */
    private static final List<Kind> ESTIMATE_KINDS = List.of(Kind.SEED, Kind.FERTILIZER, Kind.LIME, Kind.PROTECTION);
    /** Kinds booked once per booking that a rewind takes back (the counter lines follow the falling counter). */
    private static final Set<Kind> BOOKED_KINDS = Set.of(Kind.CONTRACTOR, Kind.RENT, Kind.PREMIUM, Kind.OVERHEAD);
    static final String ESTIMATE = "ESTIMATE:";
    static final String OVERHEAD = "OVERHEAD:";

    // ------------------------------------------------------------------------------------------ views

    /**
     * One line. {@code id} null = an estimate (no stored line); {@code amount} signed (costs negative), null = no
     * price known; {@code corrected} = changed by the player; {@code held} = booked for a closed year, not counted until
     * it is reopened.
     */
    public record Line(Long id, String kind, String source, String fillType, Double liters, Double helperLiters,
                       Double amount, boolean corrected, String note, boolean held) {
    }

    /**
     * Price per litre of the product: {@code source} REALISED (sales), HARVEST_DAY (best price on the harvest day),
     * INTERNAL (purchase price x share), ENTERED (by the player) or null (unknown); {@code provisional} = sales can
     * still come in.
     */
    public record Price(Double perLiter, String source, boolean provisional) {
    }

    /** The calculation; every value in euro, signed. revenue / free / margin / result null without a revenue. */
    public record Totals(Double revenue, double directCosts, Double freeOfDirectCosts, double contractor, Double margin,
                         double premiums, double rent, double overheads, Double result) {
    }

    /**
     * One field book entry with its calculation, per entry and per hectare. {@code estimated} = the direct costs are
     * estimated (older mod), {@code incomplete} = a line has no price.
     */
    public record EntryMargin(long entryId, int farmlandId, String fieldName, String status, Integer harvestYear,
                              String fruitType, String fillType, Double hectares, Double liters, Price price,
                              List<Line> lines, Totals totals, Totals perHectare, boolean locked, boolean estimated,
                              boolean incomplete) {
    }

    public record FieldMargins(int farmlandId, String name, Double hectares, EntryMargin running,
                               List<EntryMargin> entries) {
    }

    /** Average contribution margin per hectare of a field over the period, with the number of entries {@code n}. */
    public record Ranking(int farmlandId, String name, Double marginPerHectare, int n) {
    }

    /** Averages per hectare of a crop and product over all fields of the period. */
    public record CropMargin(String fruitType, String fillType, Double revenuePerHectare, Double directCostsPerHectare,
                             Double marginPerHectare, int n) {
    }

    /**
     * The card. {@code years} / {@code fromYear} / {@code toYear} = the period of the field book evaluation (shared,
     * owner decision 2026-10-13); {@code overheads} = switch "Gemeinkosten verteilen"; {@code measured} = the mod counts
     * the consumption (else the direct costs are estimated).
     */
    public record Margins(int years, int defaultYears, Integer fromYear, Integer toYear, Integer currentYear,
                          boolean overheads, boolean measured, List<FieldMargins> fields, List<Ranking> ranking,
                          List<CropMargin> crops) {
    }

    /** Kinds the player may add by hand: own costs and revenues and the replacement of an estimated line. */
    private static final Set<Kind> ADDABLE = Set.of(Kind.EXTRA_COST, Kind.EXTRA_REVENUE, Kind.SEED, Kind.FERTILIZER,
            Kind.LIME, Kind.PROTECTION);

    private final SavegameRepository savegames;
    private final FieldBookService book;
    private final FieldBookEntryRepository entries;
    private final FieldMarginItemRepository items;
    private final FieldMarginEntryRepository marginEntries;
    private final FieldInputCounterRepository counters;
    private final FactsService facts;
    private final FinanceJournalService journal;
    private final BookingEntryRepository bookings;
    private final EmployeeRepository employees;
    private final OutboxInstructionRepository outbox;
    private final ServiceCaseRepository cases;
    private final ContractRepository contracts;
    private final PriceQueryService prices;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final GameTime gameTime;
    private final RpsimProperties props;
    private final JsonMapper json;

    public FieldMarginService(SavegameRepository savegames, FieldBookService book, FieldBookEntryRepository entries,
                              FieldMarginItemRepository items, FieldMarginEntryRepository marginEntries,
                              FieldInputCounterRepository counters, FactsService facts, FinanceJournalService journal,
                              BookingEntryRepository bookings, EmployeeRepository employees,
                              OutboxInstructionRepository outbox, ServiceCaseRepository cases,
                              ContractRepository contracts, PriceQueryService prices, CharacterLookup lookup,
                              NarrationRequestService narration, GameTime gameTime, RpsimProperties props,
                              JsonMapper json) {
        this.savegames = savegames;
        this.book = book;
        this.entries = entries;
        this.items = items;
        this.marginEntries = marginEntries;
        this.counters = counters;
        this.facts = facts;
        this.journal = journal;
        this.bookings = bookings;
        this.employees = employees;
        this.outbox = outbox;
        this.cases = cases;
        this.contracts = contracts;
        this.prices = prices;
        this.lookup = lookup;
        this.narration = narration;
        this.gameTime = gameTime;
        this.props = props;
        this.json = json;
    }

    private RpsimProperties.FieldMargin cfg() {
        return props.getFormulas().getFieldMargin();
    }

    // ------------------------------------------------------------------------------------------ D1 / D2 exports

    /** After the field book (@Order(6)): the seasons of this export are already started and ended. */
    @EventListener
    @Order(7)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null) {
            return;
        }
        if (f.inputs() != null) {
            inputs(sg, f.inputs(), e.gameTime());
        }
        harvestPrices(sg, f, e.gameTime());
    }

    /** D1: the difference of every consumption counter since the last export (a falling counter is taken back). */
    void inputs(Savegame sg, List<BridgeDtos.InputCounter> list, long now) {
        Map<String, FieldInputCounter> known = new HashMap<>();
        for (FieldInputCounter c : counters.findBySavegame(sg)) {
            known.put(c.getFarmlandId() + "|" + c.getFillType(), c);
        }
        Map<String, Double> costs = purchasePrices(sg);
        for (BridgeDtos.InputCounter in : list) {
            if (in == null || in.farmlandId() == null || in.fillType() == null || in.liters() == null) {
                continue;
            }
            FieldInputCounter c = known.get(in.farmlandId() + "|" + in.fillType());
            if (c == null) {
                c = new FieldInputCounter();
                c.setSavegame(sg);
                c.setFarmlandId(in.farmlandId());
                c.setFillType(in.fillType());
            }
            double helper = in.helperLiters() == null ? 0 : in.helperLiters();
            double delta = in.liters() - c.getLastLiters();
            double helperDelta = helper - c.getLastHelperLiters();
            c.setLastLiters(in.liters());
            c.setLastHelperLiters(helper);
            counters.save(c);
            if (delta != 0) {
                bookInput(sg, in.farmlandId(), in.fillType(), delta, Math.max(0, Math.min(helperDelta, delta)), costs, now);
            }
        }
    }

    /** One counter difference into the line of the fill type of the running season of the field. */
    private void bookInput(Savegame sg, int farmlandId, String fillType, double delta, double helperDelta,
                           Map<String, Double> costs, long now) {
        FieldBookEntry running = running(sg, farmlandId).orElse(null);
        if (running == null) {
            return; // field no longer own: nothing to book
        }
        Kind kind = kindOf(fillType);
        FieldMarginItem item = items.findFirstBySavegameAndEntryIdAndKindAndReference(sg, running.getId(), kind, fillType)
                .orElseGet(() -> newItem(sg, running.getId(), kind, Source.MEASURED, fillType, now));
        item.setFillType(fillType);
        double liters = item.getLitersAuto() == null ? 0 : item.getLitersAuto();
        double helper = item.getHelperLiters() == null ? 0 : item.getHelperLiters();
        if (delta > 0) {
            Double price = kind == Kind.MANURE ? Double.valueOf(cfg().getManureCostPerLiter()) : costs.get(fillType);
            if (price != null) {
                double cost = (delta - helperDelta) * price + helperDelta * price * cfg().getHelperSurcharge();
                item.setAmountAuto((item.getAmountAuto() == null ? 0 : item.getAmountAuto()) - cost);
            }
            item.setLitersAuto(liters + delta);
            item.setHelperLiters(helper + helperDelta);
        } else {
            double after = Math.max(0, liters + delta);
            if (item.getAmountAuto() != null) {
                item.setAmountAuto(liters > 0 ? item.getAmountAuto() * after / liters : 0);
            }
            item.setLitersAuto(after);
            item.setHelperLiters(Math.min(helper, after));
        }
        item.setGameTime(now);
        items.save(item);
    }

    /** Kind of the direct costs of a fill type ({@code rpsim.formulas.field-margin.categories}). */
    Kind kindOf(String fillType) {
        String k = cfg().getCategories().get(fillType);
        if (k != null) {
            try {
                return Kind.valueOf(k);
            } catch (IllegalArgumentException ex) {
                // unknown kind in the configuration: counted as other input
            }
        }
        return Kind.OTHER_INPUT;
    }

    /** Purchase prices per litre of the game (market_context.fillTypeCosts), empty with an older mod. */
    public Map<String, Double> purchasePrices(Savegame sg) {
        Map<String, Double> m = new HashMap<>();
        facts.marketContext(sg).map(BridgeDtos.MarketContext::fillTypeCosts).ifPresent(list -> list.stream()
                .filter(c -> c != null && c.fillType() != null && c.costPerLiter() != null)
                .forEach(c -> m.put(c.fillType(), c.costPerLiter())));
        return m;
    }

    /**
     * D2: the best price of the product on the harvest day, taken once per ended entry (again when its end changed,
     * e.g. after a rewind). An entry that ended on this export day takes the prices of the export; an older one the
     * price history of its harvest day.
     */
    private void harvestPrices(Savegame sg, FarmFacts f, long now) {
        Map<Long, FieldMarginEntry> known = new HashMap<>();
        marginEntries.findBySavegame(sg).forEach(m -> known.put(m.getEntryId(), m));
        for (FieldBookEntry e : book.all(sg)) {
            if (e.getStatus() != Status.HARVESTED || e.getEndedGameTime() == null || e.fillType() == null) {
                continue;
            }
            FieldMarginEntry m = known.get(e.getId());
            if (m != null && Objects.equals(m.getEndedGameTime(), e.getEndedGameTime())) {
                continue;
            }
            if (m == null) {
                m = new FieldMarginEntry();
                m.setSavegame(sg);
                m.setEntryId(e.getId());
            }
            m.setEndedGameTime(e.getEndedGameTime());
            m.setHarvestPrice(bestPrice(sg, f, e.fillType(), e.getEndedGameTime(), now));
            marginEntries.save(m);
        }
    }

    private Double bestPrice(Savegame sg, FarmFacts f, String fillType, long at, long now) {
        if (now - at <= GameTime.MS_PER_DAY && f.prices() != null) {
            return f.prices().stream().filter(p -> p != null && fillType.equals(p.fillType()) && p.currentPrice() != null)
                    .mapToDouble(BridgeDtos.Price::currentPrice).max().stream().boxed().findFirst()
                    .map(p -> p / 1000.0).orElse(null);
        }
        return prices.history(sg, fillType, null, at, at + GameTime.MS_PER_DAY).stream()
                .filter(s -> !s.points().isEmpty()).mapToDouble(s -> s.points().getFirst().price()).max()
                .stream().boxed().findFirst().map(p -> p / 1000.0).orElse(null);
    }

    // ------------------------------------------------------------------------------------------ D1 bookings

    /** D1: contractor fees (R31-A1) and the rent of a leased field (R3-L) when the mod booked them. */
    @EventListener
    @Transactional
    public void onAck(BridgeEvents.InstructionAcked e) {
        if (!"APPLIED".equals(e.status()) || e.relatedId() == null) {
            return;
        }
        boolean contractor = ContractorWorkService.RELATED.equals(e.relatedType());
        if (!contractor && !ContractBillingService.RELATED.equals(e.relatedType())) {
            return;
        }
        OutboxInstruction o = outbox.findByInstructionId(e.instructionId()).orElse(null);
        if (o == null || o.getType() != InstructionType.MONEY_TRANSACTION) {
            return;
        }
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (items.existsBySavegameAndReference(sg, e.instructionId())) {
            return;
        }
        double amount = -Math.abs(json.readTree(o.getPayloadJson()).path("amount").asDouble(0));
        Integer farmlandId;
        Kind kind;
        String note;
        if (contractor) {
            ServiceCase sc = cases.findById(e.relatedId()).orElse(null);
            farmlandId = sc == null ? null : sc.getFarmlandId();
            kind = Kind.CONTRACTOR;
            note = sc == null ? null : ContractorWorkService.WORK_TITLES.get(sc.getReference());
        } else {
            Contract c = contracts.findById(e.relatedId()).orElse(null);
            if (c == null || c.getKind() != ContractKind.LEASE) {
                return;
            }
            farmlandId = c.getFarmlandId();
            kind = Kind.RENT;
            note = null;
        }
        if (farmlandId == null || amount == 0) {
            return;
        }
        FieldBookEntry target = running(sg, farmlandId).orElse(null);
        if (target == null) {
            return;
        }
        FieldMarginItem item = newItem(sg, target.getId(), kind, Source.MEASURED, e.instructionId(),
                sg.getCurrentGameTime());
        item.setAmountAuto(amount);
        item.setNote(note);
        items.save(item);
    }

    /**
     * D1 (owner decision 2026-10-13: own line after the margin): a premium per field goes into the entry of the field with
     * that harvest year (a harvested one first), else into its running season; a closed year holds it back.
     */
    @EventListener
    @Transactional
    public void onPremium(FieldPremiumPaid e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        for (var en : e.perField().entrySet()) {
            String reference = e.reference() + ":" + en.getKey();
            if (en.getValue() == null || en.getValue() == 0 || items.existsBySavegameAndReference(sg, reference)) {
                continue;
            }
            List<FieldBookEntry> ofField = entries.findBySavegameAndFarmlandIdOrderByIdAsc(sg, en.getKey());
            FieldBookEntry target = ofField.stream()
                    .filter(x -> x.getStatus() != Status.RUNNING && Objects.equals(x.getHarvestYear(), e.year()))
                    .min(Comparator.comparing((FieldBookEntry x) -> x.getStatus() != Status.HARVESTED)
                            .thenComparing(FieldBookEntry::getId))
                    .or(() -> ofField.stream().filter(x -> x.getStatus() == Status.RUNNING).findFirst()).orElse(null);
            if (target == null) {
                continue;
            }
            FieldMarginItem item = newItem(sg, target.getId(), Kind.PREMIUM, Source.MEASURED, reference,
                    sg.getCurrentGameTime());
            item.setAmountAuto(en.getValue());
            item.setNote(e.kind() + " " + e.year());
            item.setHeld(target.isHeld() || book.closed(sg, target.getHarvestYear()));
            items.save(item);
        }
    }

    // ------------------------------------------------------------------------------------------ D3 overheads, D6 advice

    /**
     * D3: at every month end the overheads of the month that ended (money types of the journal and the salaries of the
     * drivers) are distributed by area over the fields - into the season that ran at the start of that month. They are
     * stored always; the switch only decides whether they count. D6: at the year change the advice of the cooperative.
     */
    @EventListener
    @Order(30)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.fields() == null || f.calendar() == null || f.calendar().year() == null) {
            return;
        }
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long ended = e.monthIndex() - 1;
        int period = anchor.periodOf(ended);
        int year = anchor.periodOf(e.monthIndex()) == 1 ? f.calendar().year() - 1 : f.calendar().year();
        distribute(sg, f, year, period, ended, anchor.monthStart(ended), e.gameTime());
        if (anchor.periodOf(e.monthIndex()) == 1) {
            advise(sg, f.calendar().year() - 1);
        }
    }

    void distribute(Savegame sg, FarmFacts f, int year, int period, long monthIndex, long monthStart, long now) {
        long key = FinanceJournalService.key(year, period);
        double total = journal.months(f).stream().filter(m -> m.key() == key).findFirst()
                .map(m -> cfg().getOverheadMoneyTypes().stream().mapToDouble(t -> Math.min(0, m.amountOf(t))).sum())
                .orElse(0.0);
        if (cfg().isOverheadDriverSalaries()) {
            total -= employees.findBySavegameAndStatus(sg, EmployeeStatus.ACTIVE).stream()
                    .filter(x -> x.getJobRole() != null && x.getJobRole().drives()).mapToLong(Employee::getMonthlySalary)
                    .sum();
        }
        if (total == 0) {
            return;
        }
        Map<FieldBookEntry, Double> share = new LinkedHashMap<>();
        for (BridgeDtos.Field field : f.fields()) {
            if (field == null || field.farmlandId() == null || field.hectares() == null || field.hectares() <= 0) {
                continue;
            }
            List<FieldBookEntry> ofField = entries.findBySavegameAndFarmlandIdOrderByIdAsc(sg, field.farmlandId());
            ofField.stream().filter(x -> x.getStartedGameTime() <= monthStart
                            && (x.getEndedGameTime() == null || x.getEndedGameTime() > monthStart)).findFirst()
                    .or(() -> ofField.stream().filter(x -> x.getStatus() == Status.RUNNING).findFirst())
                    .ifPresent(x -> share.merge(x, field.hectares(), Double::sum));
        }
        double hectares = share.values().stream().mapToDouble(Double::doubleValue).sum();
        String reference = OVERHEAD + monthIndex;
        for (var en : share.entrySet()) {
            FieldBookEntry target = en.getKey();
            if (items.findFirstBySavegameAndEntryIdAndKindAndReference(sg, target.getId(), Kind.OVERHEAD, reference)
                    .isPresent()) {
                continue;
            }
            FieldMarginItem item = newItem(sg, target.getId(), Kind.OVERHEAD, Source.DISTRIBUTED, reference, now);
            item.setAmountAuto(total * en.getValue() / hectares);
            item.setNote(period + "/" + year);
            item.setHeld(target.isHeld() || book.closed(sg, target.getHarvestYear()));
            items.save(item);
        }
    }

    /**
     * D6: a field with a negative contribution margin in the harvest years {@code year - 1} and {@code year}: the
     * cooperative writes once (the figures come from here, the AI only words them).
     */
    void advise(Savegame sg, int year) {
        if (!cfg().isAdviceEnabled()) {
            return;
        }
        var cooperative = lookup.mandatory(sg, CharacterRole.COOPERATIVE);
        if (cooperative.isEmpty()) {
            return;
        }
        Context ctx = context(sg);
        Map<Integer, Map<Integer, EntryMargin>> byField = new TreeMap<>();
        for (FieldBookEntry e : book.all(sg)) {
            if (e.getStatus() != Status.RUNNING && !e.isHeld() && e.getHarvestYear() != null
                    && (e.getHarvestYear() == year || e.getHarvestYear() == year - 1)) {
                byField.computeIfAbsent(e.getFarmlandId(), k -> new TreeMap<>()).put(e.getHarvestYear(), margin(ctx, e));
            }
        }
        byField.forEach((farmlandId, years) -> {
            EntryMargin last = years.get(year);
            EntryMargin before = years.get(year - 1);
            if (last == null || before == null || !negative(last) || !negative(before)) {
                return;
            }
            FieldMarginEntry m = marginEntry(sg, last.entryId());
            if (m.isAdvised()) {
                return;
            }
            m.setAdvised(true);
            marginEntries.save(m);
            narration.request(sg, NarrationEventType.FIELD_MARGIN_ADVICE).from(cooperative.get())
                    .facts(NarrationFacts.builder().put("fieldName", last.fieldName()).put("year", year)
                            .put("fruitType", last.fruitType()).put("margin", Math.round(last.totals().margin()))
                            .put("previousFruitType", before.fruitType())
                            .put("previousMargin", Math.round(before.totals().margin())).build())
                    .category(CommunicationCategory.CONTRACT).submit();
        });
    }

    private static boolean negative(EntryMargin m) {
        return m.totals().margin() != null && m.totals().margin() < 0;
    }

    // ------------------------------------------------------------------------------------------ rewind, reopen

    /** A rewind takes back the bookings after the loaded game time; the counter lines follow the falling counters. */
    @EventListener
    @Transactional
    public void onRewound(BridgeEvents.Rewound e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        items.deleteAll(items.findBySavegameOrderByIdAsc(sg).stream()
                .filter(i -> BOOKED_KINDS.contains(i.getKind()) && i.getSource() != Source.ENTERED
                        && i.getGameTime() > e.rewoundToGameTime()).toList());
    }

    /** R33-F5: what was held back for a reopened year counts now. */
    @EventListener
    @Transactional
    public void onYearReopened(FieldYearReopenedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Set<Long> ofYear = new java.util.HashSet<>();
        book.all(sg).stream().filter(x -> Objects.equals(x.getHarvestYear(), e.year())).forEach(x -> ofYear.add(x.getId()));
        for (FieldMarginItem i : items.findBySavegameOrderByIdAsc(sg)) {
            if (i.isHeld() && ofYear.contains(i.getEntryId())) {
                i.setHeld(false);
                items.save(i);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ D4 player

    /** D4: corrects litres and / or amount of a line (null both gives it back to the capture). */
    @Transactional
    public FieldMarginItem correct(Savegame sg, long itemId, Double liters, Double amount) {
        FieldMarginItem item = ownItem(sg, itemId);
        requireOpen(sg, item.getEntryId());
        checkNumber(liters, true);
        checkNumber(amount, false);
        if (item.getSource() == Source.ENTERED) {
            if (amount == null) {
                throw new BusinessRuleException("FIELD_MARGIN_AMOUNT", "Eine eigene Zeile braucht einen Betrag.");
            }
            item.setAmountAuto(signed(item.getKind(), amount));
            item.setLitersAuto(liters);
        } else {
            item.setLitersManual(liters);
            item.setAmountManual(amount == null ? null : signed(item.getKind(), amount));
        }
        return items.save(item);
    }

    /**
     * D4: adds an own line - a cost or revenue of the entry, or (older mod) the replacement of an estimated line.
     * {@code amount} is entered positive; costs are stored negative.
     */
    @Transactional
    public FieldMarginItem add(Savegame sg, long entryId, Kind kind, String note, Double liters, double amount) {
        if (!ADDABLE.contains(kind)) {
            throw new BusinessRuleException("FIELD_MARGIN_KIND", "Diese Art kann nicht von Hand eingetragen werden.");
        }
        FieldBookEntry e = ownEntry(sg, entryId);
        requireOpen(sg, e.getId());
        checkNumber(liters, true);
        checkNumber(amount, false);
        String reference = kind == Kind.EXTRA_COST || kind == Kind.EXTRA_REVENUE ? null : ESTIMATE + kind.name();
        if (reference != null) {
            items.findFirstBySavegameAndEntryIdAndKindAndReference(sg, entryId, kind, reference).ifPresent(items::delete);
            items.flush();
        }
        FieldMarginItem item = newItem(sg, entryId, kind, Source.ENTERED, reference, sg.getCurrentGameTime());
        item.setAmountAuto(signed(kind, amount));
        item.setLitersAuto(liters);
        item.setNote(note == null || note.isBlank() ? null : note.trim());
        return items.save(item);
    }

    /** D4: removes an own line (an estimate it replaced comes back). */
    @Transactional
    public void remove(Savegame sg, long itemId) {
        FieldMarginItem item = ownItem(sg, itemId);
        requireOpen(sg, item.getEntryId());
        if (item.getSource() != Source.ENTERED) {
            throw new BusinessRuleException("FIELD_MARGIN_REMOVE", "Nur eigene Zeilen können gelöscht werden.");
        }
        items.delete(item);
    }

    /** D4: the price per litre of the product of an entry; null gives it back to the rule of D2. */
    @Transactional
    public void setPrice(Savegame sg, long entryId, Double perLiter) {
        FieldBookEntry e = ownEntry(sg, entryId);
        requireOpen(sg, e.getId());
        checkNumber(perLiter, true);
        FieldMarginEntry m = marginEntry(sg, entryId);
        m.setPriceManual(perLiter);
        marginEntries.save(m);
    }

    /** D3: switch "Gemeinkosten verteilen" per savegame. */
    @Transactional
    public void setOverheads(Savegame sg, boolean on) {
        sg.setFieldMarginOverheads(on);
        savegames.save(sg);
    }

    private static double signed(Kind kind, double amount) {
        return kind == Kind.EXTRA_REVENUE || kind == Kind.PREMIUM ? Math.abs(amount) : -Math.abs(amount);
    }

    private static void checkNumber(Double v, boolean nonNegative) {
        if (v != null && (v.isNaN() || v.isInfinite() || (nonNegative && v < 0))) {
            throw new BusinessRuleException("FIELD_MARGIN_NUMBER", "Bitte eine Zahl ab 0 eintragen.");
        }
    }

    private FieldMarginItem ownItem(Savegame sg, long id) {
        return items.findById(id).filter(i -> i.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("Zeile " + id + " nicht gefunden"));
    }

    private FieldBookEntry ownEntry(Savegame sg, long id) {
        return entries.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("Feldbuch-Eintrag " + id + " nicht gefunden"));
    }

    private void requireOpen(Savegame sg, long entryId) {
        FieldBookEntry e = ownEntry(sg, entryId);
        if (e.isHeld() || book.closed(sg, e.getHarvestYear())) {
            throw new BusinessRuleException("FIELD_BOOK_YEAR_CLOSED", "Das Erntejahr " + e.getHarvestYear()
                    + " ist abgeschlossen. Öffne es im Feldbuch wieder, um etwas zu ändern.");
        }
    }

    // ------------------------------------------------------------------------------------------ read model

    /** Everything one request needs, read once. */
    private record Context(Savegame sg, FarmFacts facts, Map<Long, List<FieldMarginItem>> items,
                           Map<Long, FieldMarginEntry> margins, Map<String, Double> costs,
                           Map<String, List<BookingEntry>> sales, Map<String, FieldBookService.Crop> crops,
                           boolean measured, boolean overheads, Integer currentYear) {
    }

    private Context context(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Map<Long, List<FieldMarginItem>> byEntry = new HashMap<>();
        items.findBySavegameOrderByIdAsc(sg).forEach(i -> byEntry.computeIfAbsent(i.getEntryId(), k -> new ArrayList<>()).add(i));
        Map<Long, FieldMarginEntry> margins = new HashMap<>();
        marginEntries.findBySavegame(sg).forEach(m -> margins.put(m.getEntryId(), m));
        Map<String, FieldBookService.Crop> crops = new HashMap<>();
        book.crops(sg).forEach(c -> crops.put(c.name(), c));
        boolean overheads = sg.getFieldMarginOverheads() == null ? cfg().isOverheadsDefault() : sg.getFieldMarginOverheads();
        Integer current = f != null && f.calendar() != null ? f.calendar().year() : null;
        return new Context(sg, f, byEntry, margins, purchasePrices(sg), new HashMap<>(), crops,
                counters.existsBySavegame(sg), overheads, current);
    }

    /** D5: the card - every field with its running season and its entries of the period, the ranking and the crops. */
    @Transactional(readOnly = true)
    public Margins margins(Savegame sg) {
        Context ctx = context(sg);
        int defaultYears = Math.max(1, props.getFormulas().getFieldBook().getDefaultYears());
        int years = sg.getFieldBookYears() == null ? defaultYears : sg.getFieldBookYears();
        List<FieldBookEntry> all = book.all(sg);
        Integer first = all.stream().filter(x -> x.getStatus() != Status.RUNNING && !x.isHeld())
                .map(FieldBookEntry::getHarvestYear).filter(Objects::nonNull).min(Integer::compare).orElse(null);
        Integer to = ctx.currentYear() != null ? ctx.currentYear()
                : all.stream().map(FieldBookEntry::getHarvestYear).filter(Objects::nonNull).max(Integer::compare).orElse(null);
        Integer from = to == null ? null : years == 0 ? first : to - years + 1;
        Map<Integer, String> names = new TreeMap<>();
        Map<Integer, Double> hectares = new TreeMap<>();
        if (ctx.facts() != null && ctx.facts().fields() != null) {
            ctx.facts().fields().stream().filter(x -> x != null && x.farmlandId() != null).forEach(x -> {
                names.put(x.farmlandId(), x.name());
                hectares.put(x.farmlandId(), x.hectares());
            });
        }
        Map<Integer, List<FieldBookEntry>> byField = new TreeMap<>();
        names.keySet().forEach(id -> byField.put(id, new ArrayList<>()));
        all.forEach(x -> byField.computeIfAbsent(x.getFarmlandId(), k -> new ArrayList<>()).add(x));
        List<FieldMargins> fields = new ArrayList<>();
        List<EntryMargin> inPeriod = new ArrayList<>();
        for (var en : byField.entrySet()) {
            int id = en.getKey();
            EntryMargin running = en.getValue().stream().filter(x -> x.getStatus() == Status.RUNNING).findFirst()
                    .map(x -> margin(ctx, x)).orElse(null);
            List<EntryMargin> done = en.getValue().stream()
                    .filter(x -> x.getStatus() != Status.RUNNING && x.getHarvestYear() != null && from != null
                            && x.getHarvestYear() >= from && x.getHarvestYear() <= to)
                    .sorted(Comparator.comparing(FieldBookEntry::getHarvestYear, Comparator.reverseOrder())
                            .thenComparing(FieldBookEntry::getId))
                    .map(x -> margin(ctx, x)).toList();
            if (running == null && done.isEmpty()) {
                continue;
            }
            done.stream().filter(m -> !entryHeld(all, m.entryId())).forEach(inPeriod::add);
            String name = names.getOrDefault(id, en.getValue().stream().map(FieldBookEntry::getFieldName)
                    .filter(Objects::nonNull).reduce((a, b) -> b).orElse(String.valueOf(id)));
            fields.add(new FieldMargins(id, name, hectares.get(id), running, done));
        }
        return new Margins(years, defaultYears, from, to, ctx.currentYear(), ctx.overheads(), ctx.measured(), fields,
                ranking(fields, inPeriod), crops(inPeriod));
    }

    private static boolean entryHeld(List<FieldBookEntry> all, long id) {
        return all.stream().anyMatch(x -> x.getId() == id && x.isHeld());
    }

    /** D6: the margins of the harvested entries of a harvest year, for the farm report. */
    @Transactional(readOnly = true)
    public List<EntryMargin> ofYear(Savegame sg, int year) {
        Context ctx = context(sg);
        return book.all(sg).stream().filter(x -> x.getStatus() != Status.RUNNING && !x.isHeld()
                && Integer.valueOf(year).equals(x.getHarvestYear())).map(x -> margin(ctx, x)).toList();
    }

    /** D5: averages per hectare of a crop and product over the period (entries without revenue do not count). */
    static List<CropMargin> crops(List<EntryMargin> list) {
        Map<List<String>, List<EntryMargin>> groups = new TreeMap<>(Comparator
                .comparing((List<String> k) -> k.get(0)).thenComparing(k -> k.get(1) == null ? "" : k.get(1)));
        for (EntryMargin m : list) {
            if (m.fruitType() != null && m.perHectare() != null && m.perHectare().margin() != null) {
                groups.computeIfAbsent(java.util.Arrays.asList(m.fruitType(), m.fillType()), k -> new ArrayList<>()).add(m);
            }
        }
        List<CropMargin> out = new ArrayList<>();
        groups.forEach((k, ms) -> out.add(new CropMargin(k.get(0), k.get(1),
                avg(ms.stream().map(m -> m.perHectare().revenue()).toList()),
                avg(ms.stream().map(m -> m.perHectare().directCosts()).toList()),
                avg(ms.stream().map(m -> m.perHectare().margin()).toList()), ms.size())));
        return out;
    }

    /** D5: the fields by their average margin per hectare over the period, best first; fields without one at the end. */
    static List<Ranking> ranking(List<FieldMargins> fields, List<EntryMargin> inPeriod) {
        List<Ranking> out = new ArrayList<>();
        for (FieldMargins fm : fields) {
            List<Double> values = inPeriod.stream().filter(m -> m.farmlandId() == fm.farmlandId())
                    .map(m -> m.perHectare() == null ? null : m.perHectare().margin()).filter(Objects::nonNull).toList();
            out.add(new Ranking(fm.farmlandId(), fm.name(), avg(values), values.size()));
        }
        out.sort(Comparator.comparing((Ranking r) -> r.marginPerHectare() == null)
                .thenComparing(r -> r.marginPerHectare() == null ? 0 : -r.marginPerHectare())
                .thenComparingInt(Ranking::farmlandId));
        return out;
    }

    private static Double avg(List<Double> values) {
        List<Double> v = values.stream().filter(Objects::nonNull).toList();
        return v.isEmpty() ? null : v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** The calculation of one entry. */
    private EntryMargin margin(Context ctx, FieldBookEntry e) {
        boolean running = e.getStatus() == Status.RUNNING;
        Double hectares = e.getHectares();
        if (hectares == null && ctx.facts() != null && ctx.facts().fields() != null) {
            hectares = ctx.facts().fields().stream().filter(x -> x != null && Integer.valueOf(e.getFarmlandId()).equals(x.farmlandId()))
                    .map(BridgeDtos.Field::hectares).filter(Objects::nonNull).findFirst().orElse(null);
        }
        List<FieldMarginItem> stored = ctx.items().getOrDefault(e.getId(), List.of());
        List<Line> lines = new ArrayList<>();
        stored.stream().sorted(Comparator.comparing(FieldMarginItem::getKind).thenComparing(FieldMarginItem::getId))
                .forEach(i -> lines.add(new Line(i.getId(), i.getKind().name(), i.getSource().name(), i.getFillType(),
                        i.liters(), i.getHelperLiters(), i.amount(), i.corrected() || i.getSource() == Source.ENTERED,
                        i.getNote(), i.isHeld())));
        boolean estimated = false;
        if (!ctx.measured() && stored.stream().noneMatch(i -> INPUT_KINDS.contains(i.getKind()) && i.getSource() == Source.MEASURED)) {
            for (Line l : estimates(ctx, e, hectares, stored)) {
                lines.add(l);
                estimated = true;
            }
        }
        Price price = running ? new Price(null, null, false) : price(ctx, e);
        Double liters = e.liters();
        Map<Kind, Double> sums = new EnumMap<>(Kind.class);
        boolean incomplete = false;
        for (Line l : lines) {
            if (l.held()) {
                continue;
            }
            if (l.amount() == null) {
                incomplete = true;
                continue;
            }
            sums.merge(Kind.valueOf(l.kind()), l.amount(), Double::sum);
        }
        double extraRevenue = sums.getOrDefault(Kind.EXTRA_REVENUE, 0.0);
        Double crop = liters != null && price.perLiter() != null ? liters * price.perLiter() : null;
        Double revenue = crop == null && extraRevenue == 0 ? null : (crop == null ? 0 : crop) + extraRevenue;
        double direct = sums.entrySet().stream().filter(x -> x.getKey().direct()).mapToDouble(Map.Entry::getValue).sum();
        double contractor = sums.getOrDefault(Kind.CONTRACTOR, 0.0);
        double premiums = sums.getOrDefault(Kind.PREMIUM, 0.0);
        double rent = sums.getOrDefault(Kind.RENT, 0.0);
        double overheads = ctx.overheads() ? sums.getOrDefault(Kind.OVERHEAD, 0.0) : 0;
        Totals totals = totals(revenue, direct, contractor, premiums, rent, overheads);
        Totals perHa = hectares == null || hectares <= 0 ? null : perHectare(totals, hectares);
        boolean locked = e.isHeld() || ctx.sg() != null && book.closed(ctx.sg(), e.getHarvestYear());
        return new EntryMargin(e.getId(), e.getFarmlandId(), e.getFieldName(), e.getStatus().name(), e.getHarvestYear(),
                e.fruitType(), e.fillType(), hectares, liters, price, lines, totals, perHa, locked, estimated, incomplete);
    }

    static Totals totals(Double revenue, double direct, double contractor, double premiums, double rent, double overheads) {
        Double free = revenue == null ? null : revenue + direct;
        Double margin = free == null ? null : free + contractor;
        Double result = margin == null ? null : margin + premiums + rent + overheads;
        return new Totals(revenue, direct, free, contractor, margin, premiums, rent, overheads, result);
    }

    private static Totals perHectare(Totals t, double ha) {
        return new Totals(div(t.revenue(), ha), t.directCosts() / ha, div(t.freeOfDirectCosts(), ha), t.contractor() / ha,
                div(t.margin(), ha), t.premiums() / ha, t.rent() / ha, t.overheads() / ha, div(t.result(), ha));
    }

    private static Double div(Double v, double ha) {
        return v == null ? null : v / ha;
    }

    /**
     * D1 estimate without the counter (older mod), marked as such: seed = seed use of the crop x purchase price (else
     * the fallback per ha), fertiliser per tick, lime, plant protection per ha. An entered line of the same kind
     * replaces the estimate.
     */
    private List<Line> estimates(Context ctx, FieldBookEntry e, Double ha, List<FieldMarginItem> stored) {
        List<Line> out = new ArrayList<>();
        if (ha == null || ha <= 0) {
            return out;
        }
        var c = cfg();
        Map<Kind, Double> perHa = new EnumMap<>(Kind.class);
        if (e.fruitType() != null) {
            Double seedUse = seedUse(ctx, e.fruitType());
            Double seedPrice = ctx.costs().get("SEEDS");
            perHa.put(Kind.SEED, seedUse != null && seedPrice != null ? seedUse * 10000 * seedPrice : c.getFallbackSeedCostPerHa());
        }
        int ticks = (e.fert1() ? 1 : 0) + (e.fert2() ? 1 : 0);
        if (ticks > 0) {
            perHa.put(Kind.FERTILIZER, ticks * c.getFallbackFertilizerCostPerHa());
        }
        if (e.limed()) {
            perHa.put(Kind.LIME, c.getFallbackLimeCostPerHa());
        }
        if (e.weeds()) {
            perHa.put(Kind.PROTECTION, c.getFallbackProtectionCostPerHa());
        }
        for (Kind k : ESTIMATE_KINDS) {
            Double v = perHa.get(k);
            boolean replaced = stored.stream().anyMatch(i -> i.getKind() == k && (ESTIMATE + k.name()).equals(i.getReference()));
            if (v != null && !replaced) {
                out.add(new Line(null, k.name(), "ESTIMATED", null, null, null, -v * ha, false, null, false));
            }
        }
        return out;
    }

    private Double seedUse(Context ctx, String fruitType) {
        return facts.marketContext(ctx.sg()).map(BridgeDtos.MarketContext::fruitTypes).orElse(List.of()).stream()
                .filter(t -> t != null && fruitType.equals(t.name())).map(BridgeDtos.FruitTypeEntry::seedUsagePerSqm)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * D2 (owner decision 2026-10-13): the price the player entered; else the realised average of the sales of the
     * product from the harvest to the end of the following FS25 year; else the best price on the harvest day; else the
     * purchase price x share (a product without a sell point, e.g. silage for the own animals).
     */
    private Price price(Context ctx, FieldBookEntry e) {
        FieldMarginEntry m = ctx.margins().get(e.getId());
        if (m != null && m.getPriceManual() != null) {
            return new Price(m.getPriceManual(), "ENTERED", false);
        }
        String product = e.fillType();
        if (product == null) {
            return new Price(null, null, false);
        }
        boolean provisional = e.getHarvestYear() == null || ctx.currentYear() == null
                || ctx.currentYear() <= e.getHarvestYear() + 1;
        List<BookingEntry> sales = ctx.sales().computeIfAbsent(product,
                p -> bookings.findBySavegameAndFillTypeOrderBySeqAsc(ctx.sg(), p));
        long from = e.getEndedGameTime() == null ? 0 : e.getEndedGameTime();
        double liters = 0;
        double amount = 0;
        for (BookingEntry b : sales) {
            if (b.getLiters() != null && b.getLiters() > 0 && b.getAmount() > 0 && b.getGameTime() >= from
                    && cfg().getRevenueCategories().contains(b.getCategory()) && e.getHarvestYear() != null
                    && (b.getYear() == e.getHarvestYear() || b.getYear() == e.getHarvestYear() + 1)) {
                liters += b.getLiters();
                amount += b.getAmount();
            }
        }
        if (liters > 0) {
            return new Price(amount / liters, "REALISED", provisional);
        }
        if (m != null && m.getHarvestPrice() != null) {
            return new Price(m.getHarvestPrice(), "HARVEST_DAY", provisional);
        }
        Double cost = ctx.costs().get(product);
        if (cost != null) {
            return new Price(cost * cfg().getInternalPriceShare(), "INTERNAL", false);
        }
        return new Price(null, null, provisional);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Optional<FieldBookEntry> running(Savegame sg, int farmlandId) {
        return entries.findBySavegameAndFarmlandIdOrderByIdAsc(sg, farmlandId).stream()
                .filter(x -> x.getStatus() == Status.RUNNING).findFirst();
    }

    private FieldMarginEntry marginEntry(Savegame sg, long entryId) {
        return marginEntries.findBySavegameAndEntryId(sg, entryId).orElseGet(() -> {
            FieldMarginEntry m = new FieldMarginEntry();
            m.setSavegame(sg);
            m.setEntryId(entryId);
            return m;
        });
    }

    private static FieldMarginItem newItem(Savegame sg, long entryId, Kind kind, Source source, String reference,
                                           long gameTime) {
        FieldMarginItem i = new FieldMarginItem();
        i.setSavegame(sg);
        i.setEntryId(entryId);
        i.setKind(kind);
        i.setSource(source);
        i.setReference(reference);
        i.setGameTime(gameTime);
        return i;
    }
}
