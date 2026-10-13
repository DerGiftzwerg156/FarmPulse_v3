package de.farmpulse.rpsim.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.contract.ContractBillingService;
import de.farmpulse.rpsim.domain.BookingEntry;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.FieldBookEntry.Status;
import de.farmpulse.rpsim.domain.FieldMarginItem.Kind;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.farmwork.ContractorWorkService;
import de.farmpulse.rpsim.field.FieldMarginService.EntryMargin;
import de.farmpulse.rpsim.field.FieldMarginService.Line;
import de.farmpulse.rpsim.repository.BookingEntryRepository;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V4 section D: direct costs from the consumption counter (D1), the estimate of an older mod, contractor, rent
 * and premiums, the revenue price rule (D2), overheads by area (D3), corrections and own lines (D4), the merge of field
 * book entries, closed years and the advice of the cooperative (D6) - owner decisions 2026-10-13.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FieldMarginTest {

    @Autowired Fixtures fx;
    @Autowired FieldBookService book;
    @Autowired FieldMarginService margins;
    @Autowired FactsService facts;
    @Autowired OutboxService outbox;
    @Autowired ServiceCaseRepository cases;
    @Autowired ContractRepository contracts;
    @Autowired BookingEntryRepository bookings;

    Savegame sg;
    long t;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        t = sg.getCurrentGameTime();
        context("{ \"fillType\": \"SEEDS\", \"costPerLiter\": 0.9 }, { \"fillType\": \"FERTILIZER\", \"costPerLiter\": 1.6 },"
                + " { \"fillType\": \"LIQUIDMANURE\", \"costPerLiter\": 0.02 }");
    }

    private void context(String costs) {
        sg.setMarketContextJson("""
                { "savegameId": "%s", "mapName": "m", "sellPoints": [], "fillTypes": [], "farmlands": [],
                  "fruitTypes": [{ "name": "WHEAT", "fillType": "WHEAT", "regrows": false, "needsRolling": true },
                                 { "name": "BARLEY", "fillType": "BARLEY", "regrows": false, "needsRolling": true }],
                  "fillTypeCosts": [%s] }""".formatted(sg.getBridgeSavegameId(), costs));
    }

    /** Field 12 (4.5 ha): crop null = empty; ripe at 8; harvested = cut. */
    private static String field(String crop, int gs, boolean cut, int spray, int lime, int weed) {
        String cropPart = crop == null ? "" : """
                "fruitType": "%s", "minHarvestingGrowthState": 8, "maxHarvestingGrowthState": 8, "fillType": "%s",
                "withered": false, "cut": %s, """.formatted(crop, crop, cut);
        return """
                { "farmlandId": 12, "name": "12", "hectares": 4.5, %s"growthState": %d, "weedState": %d, "stoneLevel": 0,
                  "sprayLevel": %d, "limeLevel": %d, "plowLevel": 1 }""".formatted(cropPart, gs, weed, spray, lime);
    }

    private static String input(String fillType, double liters, double helper) {
        return "{ \"farmlandId\": 12, \"fillType\": \"%s\", \"liters\": %s, \"helperLiters\": %s }".formatted(fillType,
                liters, helper);
    }

    /** One export: field 12, optional harvest counter and consumption counter (null = older mod without the block). */
    private void export(int year, String field, String harvests, String inputs) {
        t += GameTime.hours(1);
        sg.setCurrentGameTime(t);
        String extra = "\"calendar\": { \"period\": 1, \"dayInPeriod\": 1, \"daysPerPeriod\": 1, \"year\": " + year
                + ", \"monotonicDay\": " + t / 86_400_000L + " }, \"fields\": [" + field + "]"
                + (harvests == null ? "" : ", \"harvests\": [" + harvests + "]")
                + (inputs == null ? "" : ", \"inputs\": [" + inputs + "]");
        var s = fx.snapshot(sg, t, 100_000, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                extra));
        var event = new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false);
        book.onFacts(event);
        margins.onFacts(event);
    }

    private static String wheat(double liters) {
        return "{ \"farmlandId\": 12, \"fruitType\": \"WHEAT\", \"fillType\": \"WHEAT\", \"liters\": " + liters + " }";
    }

    private EntryMargin runningMargin() {
        return margins.margins(sg).fields().getFirst().running();
    }

    private EntryMargin harvested() {
        return margins.margins(sg).fields().getFirst().entries().getFirst();
    }

    private static Line line(EntryMargin m, String kind) {
        return m.lines().stream().filter(l -> l.kind().equals(kind)).findFirst().orElseThrow();
    }

    /** Sows wheat with 3000 l seed, fertilises with 1000 l (400 l bought by a helper) and slurry, then harvests. */
    private void season() {
        export(1, field(null, 0, false, 0, 0, 0), null, "");
        export(1, field("WHEAT", 1, false, 0, 0, 0), null, input("SEEDS", 3000, 0));
        export(1, field("WHEAT", 4, false, 1, 0, 0), null, input("SEEDS", 3000, 0) + ", "
                + input("FERTILIZER", 1000, 400) + ", " + input("LIQUIDMANURE", 50000, 0));
        export(1, field("WHEAT", 8, false, 1, 0, 0), wheat(0), input("SEEDS", 3000, 0) + ", "
                + input("FERTILIZER", 1000, 400) + ", " + input("LIQUIDMANURE", 50000, 0));
        export(2, field("WHEAT", 9, true, 0, 0, 0), wheat(36000), input("SEEDS", 3000, 0) + ", "
                + input("FERTILIZER", 1000, 400) + ", " + input("LIQUIDMANURE", 50000, 0));
    }

    @Test
    void theCounterBooksDirectCostsAtThePriceOfTheMomentAndTheHelperSurcharge() {
        season();
        EntryMargin m = harvested();
        assertThat(m.status()).isEqualTo("HARVESTED");
        assertThat(line(m, "SEED").amount()).isCloseTo(-2700, within(0.01));
        assertThat(line(m, "SEED").liters()).isEqualTo(3000.0);
        // 600 l x 1.6 + 400 l x 1.6 x 1.5 (the helper bought them)
        assertThat(line(m, "FERTILIZER").amount()).isCloseTo(-1920, within(0.01));
        assertThat(line(m, "FERTILIZER").helperLiters()).isEqualTo(400.0);
        // own slurry costs nothing, its litres are shown (owner decision 2026-10-13)
        assertThat(line(m, "MANURE").amount()).isEqualTo(0.0);
        assertThat(line(m, "MANURE").liters()).isEqualTo(50000.0);
        assertThat(m.totals().directCosts()).isCloseTo(-4620, within(0.01));
        assertThat(m.estimated()).isFalse();
        // revenue: best price on the harvest day (230 per 1000 l) x 36000 l
        assertThat(m.price().source()).isEqualTo("HARVEST_DAY");
        assertThat(m.price().perLiter()).isEqualTo(0.23);
        assertThat(m.totals().revenue()).isCloseTo(8280, within(0.01));
        assertThat(m.totals().margin()).isCloseTo(3660, within(0.01));
        assertThat(m.perHectare().margin()).isCloseTo(3660 / 4.5, within(0.01));
        assertThat(margins.margins(sg).measured()).isTrue();
    }

    @Test
    void aLaterPriceOnlyValuesTheNewLitresAndAFallingCounterTakesLitresBack() {
        export(1, field(null, 0, false, 0, 0, 0), null, "");
        export(1, field("WHEAT", 1, false, 0, 0, 0), null, input("SEEDS", 1000, 0));
        context("{ \"fillType\": \"SEEDS\", \"costPerLiter\": 1.2 }");
        export(1, field("WHEAT", 2, false, 0, 0, 0), null, input("SEEDS", 1500, 0));
        assertThat(line(runningMargin(), "SEED").amount()).isCloseTo(-1500, within(0.01));
        // reload of an older savegame: the counter falls, the line shrinks proportionally
        export(1, field("WHEAT", 2, false, 0, 0, 0), null, input("SEEDS", 750, 0));
        assertThat(line(runningMargin(), "SEED").liters()).isEqualTo(750.0);
        assertThat(line(runningMargin(), "SEED").amount()).isCloseTo(-750, within(0.01));
    }

    @Test
    void withoutTheCounterTheDirectCostsAreEstimatedAndAnEnteredLineReplacesTheEstimate() {
        export(1, field(null, 0, false, 0, 0, 0), null, null);
        export(1, field("WHEAT", 1, false, 0, 0, 0), null, null);
        export(1, field("WHEAT", 4, false, 1, 1, 3), null, null);
        export(1, field("WHEAT", 5, false, 2, 1, 1), null, null);
        EntryMargin m = runningMargin();
        assertThat(m.estimated()).isTrue();
        assertThat(margins.margins(sg).measured()).isFalse();
        assertThat(line(m, "SEED").source()).isEqualTo("ESTIMATED");
        assertThat(line(m, "SEED").amount()).isCloseTo(-120 * 4.5, within(0.01));
        assertThat(line(m, "FERTILIZER").amount()).as("two ticks").isCloseTo(-2 * 90 * 4.5, within(0.01));
        assertThat(line(m, "LIME").amount()).isCloseTo(-70 * 4.5, within(0.01));
        assertThat(line(m, "PROTECTION").amount()).isCloseTo(-60 * 4.5, within(0.01));
        margins.add(sg, m.entryId(), Kind.SEED, "Saatgut gekauft", null, 400);
        EntryMargin after = runningMargin();
        assertThat(after.lines().stream().filter(l -> l.kind().equals("SEED")).toList()).singleElement()
                .satisfies(l -> {
                    assertThat(l.source()).isEqualTo("ENTERED");
                    assertThat(l.amount()).isEqualTo(-400.0);
                });
    }

    @Test
    void contractorFeesRentAndPremiumsGoIntoTheirEntries() {
        season();
        long running = runningMargin().entryId();
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.CONTRACTOR_WORK);
        sc.setStatus(CaseStatus.IN_PROGRESS);
        sc.setFarmlandId(12);
        sc.setReference("PLOW");
        sc.setGameTime(t);
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        var fee = outbox.money(sg, -450, MoneyReason.CONTRACTOR_FEE, "Pflügen Feld 12",
                new OutboxService.Related(ContractorWorkService.RELATED, sc.getId()));
        var ack = new BridgeEvents.InstructionAcked(sg.getId(), fee.getInstructionId(), "APPLIED",
                ContractorWorkService.RELATED, sc.getId());
        margins.onAck(ack);
        margins.onAck(ack); // a repeated ack books nothing twice
        Contract lease = new Contract();
        lease.setSavegame(sg);
        lease.setKind(ContractKind.LEASE);
        lease.setStatus(ContractStatus.ACTIVE);
        lease.setFarmlandId(12);
        lease.setMonthlyAmount(300);
        lease.setCreatedAt(Instant.now());
        contracts.save(lease);
        var rent = outbox.money(sg, -300, MoneyReason.LEASE_PAYMENT, "Pacht Feld 12",
                new OutboxService.Related(ContractBillingService.RELATED, lease.getId()));
        margins.onAck(new BridgeEvents.InstructionAcked(sg.getId(), rent.getInstructionId(), "APPLIED",
                ContractBillingService.RELATED, lease.getId()));
        EntryMargin r = runningMargin();
        assertThat(r.entryId()).isEqualTo(running);
        assertThat(line(r, "CONTRACTOR").amount()).isEqualTo(-450.0);
        assertThat(line(r, "CONTRACTOR").note()).isEqualTo("Pflügen");
        assertThat(r.totals().contractor()).isEqualTo(-450.0);
        assertThat(r.totals().rent()).isEqualTo(-300.0);
        // the area payment of harvest year 2 goes into the harvested entry of year 2, after the margin
        margins.onPremium(new FieldPremiumPaid(sg.getId(), "Flächenprämie", 2, Map.of(12, 900.0), "DIRECT_PAYMENT:1"));
        margins.onPremium(new FieldPremiumPaid(sg.getId(), "Flächenprämie", 2, Map.of(12, 900.0), "DIRECT_PAYMENT:1"));
        EntryMargin h = harvested();
        assertThat(h.totals().premiums()).isEqualTo(900.0);
        assertThat(h.totals().result()).isCloseTo(h.totals().margin() + 900, within(0.01));
    }

    @Test
    void theRevenueUsesTheRealisedPriceOfTheSalesAfterTheHarvest() {
        season();
        long ended = harvested().entryId();
        FieldBookEntry e = book.all(sg).stream().filter(x -> x.getId() == ended).findFirst().orElseThrow();
        sale(1, e.getEndedGameTime() - 1, 1, 10000, 3000); // before the harvest: does not count
        sale(2, e.getEndedGameTime() + 1, 2, 20000, 5000);
        sale(3, e.getEndedGameTime() + 2, 3, 10000, 2000);
        sale(4, e.getEndedGameTime() + 3, 4, 10000, 9000); // year 4: after the following year
        EntryMargin m = harvested();
        assertThat(m.price().source()).isEqualTo("REALISED");
        assertThat(m.price().perLiter()).isCloseTo(7000 / 30000.0, within(1e-9));
        assertThat(m.price().provisional()).as("year 3 is not over").isTrue();
        margins.setPrice(sg, ended, 0.3);
        assertThat(harvested().price()).satisfies(p -> {
            assertThat(p.source()).isEqualTo("ENTERED");
            assertThat(p.perLiter()).isEqualTo(0.3);
        });
        margins.setPrice(sg, ended, null);
        assertThat(harvested().price().source()).isEqualTo("REALISED");
    }

    private void sale(long seq, long gameTime, int year, long liters, long amount) {
        BookingEntry b = new BookingEntry();
        b.setSavegame(sg);
        b.setSeq(seq);
        b.setGameTime(gameTime);
        b.setYear(year);
        b.setPeriod(5);
        b.setCategory("SOLD_PRODUCTS");
        b.setAmount(amount);
        b.setLiters(liters);
        b.setFillType("WHEAT");
        b.setSellPoint("MillSouth");
        bookings.save(b);
    }

    @Test
    void overheadsAreDistributedByAreaAndCountOnlyWhileTheSwitchIsOn() {
        export(1, field("WHEAT", 2, false, 0, 0, 0), null, "");
        var f = TestData.withFields(TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), t, 100_000, 1, 4,
                "[{ \"year\": 1, \"period\": 4, \"byType\": { \"PURCHASE_FUEL\": -900, \"VEHICLE_REPAIR\": -300,"
                        + " \"SOLD_PRODUCTS\": 5000, \"AI\": 100 } }]"),
                "\"fields\": [" + field("WHEAT", 2, false, 0, 0, 0) + "]");
        var s = fx.snapshot(sg, t, 100_000, f);
        margins.distribute(sg, facts.parse(s), 1, 4, 3, 0, t);
        margins.distribute(sg, facts.parse(s), 1, 4, 3, 0, t); // once per month
        EntryMargin m = runningMargin();
        assertThat(line(m, "OVERHEAD").source()).isEqualTo("DISTRIBUTED");
        assertThat(line(m, "OVERHEAD").amount()).as("only one field: everything").isCloseTo(-1200, within(0.01));
        assertThat(m.totals().overheads()).isCloseTo(-1200, within(0.01));
        margins.setOverheads(sg, false);
        assertThat(runningMargin().totals().overheads()).isEqualTo(0.0);
        assertThat(margins.margins(sg).overheads()).isFalse();
    }

    @Test
    void correctionsWinAndCanBeGivenBackOwnLinesCanBeRemoved() {
        season();
        EntryMargin m = harvested();
        long seed = line(m, "SEED").id();
        margins.correct(sg, seed, 2500.0, null);
        assertThat(line(harvested(), "SEED").amount()).as("scaled to the corrected litres").isCloseTo(-2250, within(0.01));
        assertThat(line(harvested(), "SEED").corrected()).isTrue();
        margins.correct(sg, seed, null, 1000.0);
        assertThat(line(harvested(), "SEED").amount()).isEqualTo(-1000.0);
        margins.correct(sg, seed, null, null);
        assertThat(line(harvested(), "SEED").amount()).isCloseTo(-2700, within(0.01));
        var straw = margins.add(sg, m.entryId(), Kind.EXTRA_REVENUE, "Stroh gepresst", 20000.0, 1200);
        assertThat(harvested().totals().revenue()).isCloseTo(8280 + 1200, within(0.01));
        assertThatThrownBy(() -> margins.remove(sg, seed)).isInstanceOf(BusinessRuleException.class);
        margins.remove(sg, straw.getId());
        assertThat(harvested().totals().revenue()).isCloseTo(8280, within(0.01));
        assertThatThrownBy(() -> margins.add(sg, m.entryId(), Kind.OVERHEAD, null, null, 1))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void aClosedYearLocksTheCalculationAndHoldsLatePremiumsBack() {
        season();
        long entry = harvested().entryId();
        book.closeYear(sg, 2);
        assertThat(harvested().locked()).isTrue();
        assertThatThrownBy(() -> margins.setPrice(sg, entry, 0.5)).isInstanceOf(BusinessRuleException.class);
        margins.onPremium(new FieldPremiumPaid(sg.getId(), "Fruchtfolgeprämie", 2, Map.of(12, 200.0), "ROTATION:2"));
        assertThat(line(harvested(), "PREMIUM").held()).isTrue();
        assertThat(harvested().totals().premiums()).isEqualTo(0.0);
        book.reopenYear(sg, 2);
        margins.onYearReopened(new FieldYearReopenedEvent(sg.getId(), 2));
        assertThat(harvested().totals().premiums()).isEqualTo(200.0);
    }

    @Test
    void theLinesOfAReplacedEntryMoveToTheEntryThatTakesItsPlace() {
        export(1, field(null, 0, false, 0, 0, 0), null, "");
        export(1, field("BARLEY", 2, false, 0, 0, 0), null, input("SEEDS", 1000, 0));
        // the barley is replaced without a harvest, wheat is sown and harvested in the same year
        export(1, field("WHEAT", 1, false, 0, 0, 0), null, input("SEEDS", 1000, 0));
        export(1, field("WHEAT", 1, false, 0, 0, 0), null, input("SEEDS", 3000, 0));
        export(1, field("WHEAT", 8, false, 0, 0, 0), wheat(0), input("SEEDS", 3000, 0));
        export(1, field("WHEAT", 9, true, 0, 0, 0), wheat(20000), input("SEEDS", 3000, 0));
        List<FieldBookEntry> done = book.all(sg).stream().filter(x -> x.getStatus() != Status.RUNNING).toList();
        assertThat(done).singleElement().satisfies(x -> assertThat(x.getStatus()).isEqualTo(Status.HARVESTED));
        EntryMargin m = harvested();
        assertThat(m.lines().stream().filter(l -> l.kind().equals("SEED")).mapToDouble(Line::liters).sum())
                .as("the barley seed stays in the year").isEqualTo(3000.0);
    }

    @Test
    void theCooperativeAdvisesOnceAfterTwoLossYearsInARow() {
        export(1, field(null, 0, false, 0, 0, 0), null, "");
        export(1, field("WHEAT", 8, false, 0, 0, 0), wheat(0), input("SEEDS", 20000, 0));
        export(1, field("WHEAT", 9, true, 0, 0, 0), wheat(1000), input("SEEDS", 20000, 0));
        export(2, field("WHEAT", 8, false, 0, 0, 0), wheat(1000), input("SEEDS", 40000, 0));
        export(2, field("WHEAT", 9, true, 0, 0, 0), wheat(2000), input("SEEDS", 40000, 0));
        fx.character(sg, de.farmpulse.rpsim.domain.CharacterRole.COOPERATIVE,
                de.farmpulse.rpsim.domain.CharacterCategory.MANDATORY, "Gerd Genosse");
        margins.advise(sg, 2);
        List<EntryMargin> years = margins.margins(sg).fields().getFirst().entries();
        assertThat(years).hasSize(2).allSatisfy(m -> assertThat(m.totals().margin()).isNegative());
        long last = years.getFirst().entryId();
        assertThat(marginAdvised(last)).isTrue();
        margins.advise(sg, 2); // once
    }

    @Autowired de.farmpulse.rpsim.repository.FieldMarginEntryRepository marginEntries;

    private boolean marginAdvised(long entryId) {
        return marginEntries.findBySavegameAndEntryId(sg, entryId).map(de.farmpulse.rpsim.domain.FieldMarginEntry::isAdvised)
                .orElse(false);
    }
}
