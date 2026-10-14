package de.farmpulse.rpsim.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CropPlan;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.FieldBookEntry.Status;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.planning.CropPlanService.Check;
import de.farmpulse.rpsim.planning.CropPlanService.FieldPlan;
import de.farmpulse.rpsim.planning.CropPlanService.Planning;
import de.farmpulse.rpsim.repository.CropPlanRepository;
import de.farmpulse.rpsim.repository.FieldBookEntryRepository;
import de.farmpulse.rpsim.repository.ForwardContractRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.tablet.CalendarPlanService;
import de.farmpulse.rpsim.tablet.TaskService;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V4 section A: the plan per field for the next season (A1), the hints (A2), the forecast with its sources
 * (A3), seed, marketing and the liquidity plan (A4), the task, the calendar and the reminder at sowing time (A5), plan
 * and reality, the rewind and "Plan besprechen" (A6) - owner decisions 2026-10-13.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class CropPlanTest {

    @Autowired Fixtures fx;
    @Autowired CropPlanService planner;
    @Autowired CropPlanRepository plans;
    @Autowired FieldBookEntryRepository book;
    @Autowired ForwardContractRepository forwardContracts;
    @Autowired NarrationJobRepository jobs;
    @Autowired TaskService tasks;
    @Autowired CalendarPlanService calendar;

    Savegame sg;
    long t;

    /**
     * One day per game month; now = day 10 = month 10 = August (period 6). Wheat is sown in August / September and
     * harvested in June / July (periods 4, 5), barley the same with harvest in May / June; maize gives grain or chaff.
     */
    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        t = sg.getCurrentGameTime();
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(8);
        sg.setMarketContextJson("""
                { "savegameId": "%s", "mapName": "m", "sellPoints": [], "fillTypes": [], "farmlands": [],
                  "fruitTypes": [
                    { "name": "WHEAT", "fillType": "WHEAT", "regrows": false, "products": [], "seedUsagePerSqm": 0.1,
                      "litersPerSqm": 0.9, "plantablePeriods": [6, 7], "harvestablePeriods": [4, 5] },
                    { "name": "BARLEY", "fillType": "BARLEY", "regrows": false, "products": [], "seedUsagePerSqm": 0.1,
                      "litersPerSqm": 0.7, "plantablePeriods": [6, 7], "harvestablePeriods": [3, 4] },
                    { "name": "MAIZE", "fillType": "MAIZE", "regrows": false, "products": ["CHAFF"],
                      "seedUsagePerSqm": 0.05, "litersPerSqm": 0.8, "plantablePeriods": [2, 3], "harvestablePeriods": [7, 8] }],
                  "fillTypeCosts": [{ "fillType": "SEEDS", "costPerLiter": 0.9 }, { "fillType": "BARLEY", "costPerLiter": 0.3 }] }"""
                .formatted(sg.getBridgeSavegameId()));
        facts(true, "[{ \"fillType\": \"WHEAT\", \"amount\": 0, \"freeCapacity\": 50000 }]");
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Raiffeisen Erlengrund");
        // field 12: wheat harvested in year 1 (8000 l/ha) and wheat sown again; field 14: nothing sown yet
        entry(12, Status.HARVESTED, "WHEAT", 1, 36_000.0, 4.5, true);
        entry(12, Status.RUNNING, "WHEAT", null, null, null, true);
        entry(14, Status.RUNNING, null, null, null, null, false);
    }

    /** The last farm_facts: fields 12 (4.5 ha) and 14 (2 ha), year 2, wheat 230 per 1000 l, barley 180. */
    private void facts(boolean field14, String tradeStorage) {
        sg.setCurrentGameTime(t);
        String fields = "{ \"farmlandId\": 12, \"name\": \"12\", \"hectares\": 4.5 }"
                + (field14 ? ", { \"farmlandId\": 14, \"name\": \"14\", \"hectares\": 2 }" : "");
        String doc = """
                { "schemaVersion": 1, "gameTime": %d, "savegameId": "%s", "liquidity": { "balance": 100000 },
                  "assets": { "vehicles": [], "placeables": [], "farmland": [], "animals": [], "storage": [] },
                  "liabilities": {}, "prices": [{ "sellPoint": "Mill", "fillType": "WHEAT", "currentPrice": 230 },
                                                 { "sellPoint": "Mill", "fillType": "BARLEY", "currentPrice": 180 }],
                  "calendar": { "period": 6, "dayInPeriod": 1, "daysPerPeriod": 1, "year": 2, "monotonicDay": %d },
                  "fieldRules": { "plowingRequired": false, "limeRequired": true, "weedsEnabled": false, "stonesEnabled": false },
                  "fields": [%s], "tradeStorage": %s }""".formatted(t, sg.getBridgeSavegameId(), t / GameTime.MS_PER_DAY,
                fields, tradeStorage);
        var s = fx.snapshot(sg, t, 100_000, doc);
        planner.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private FieldBookEntry entry(int farmlandId, Status status, String crop, Integer year, Double liters, Double ha,
                                 boolean limed) {
        FieldBookEntry e = new FieldBookEntry();
        e.setSavegame(sg);
        e.setFarmlandId(farmlandId);
        e.setFieldName(String.valueOf(farmlandId));
        e.setStatus(status);
        e.setFruitTypeAuto(crop);
        e.setFillTypeAuto(crop);
        e.setHarvestYear(year);
        e.setLitersAuto(liters);
        e.setHectares(ha);
        e.setLimedAuto(limed);
        e.setStartedGameTime(0);
        return book.save(e);
    }

    private static FieldPlan field(Planning p, int id) {
        return p.fields().stream().filter(f -> f.farmlandId() == id).findFirst().orElseThrow();
    }

    private static List<String> codes(FieldPlan f) {
        return f.plan().checks().stream().map(Check::code).toList();
    }

    private long count(String type) {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).filter(type::equals).count();
    }

    @Test
    void aPlanIsForTheNextSeasonOrForTheRunningOneWhenNothingIsSown() {
        planner.plan(sg, 12, "BARLEY", null, null, "Wintergerste");
        planner.plan(sg, 14, "WHEAT", null, 7, null);
        Planning p = planner.planning(sg);
        assertThat(field(p, 12).plan().target()).isEqualTo("NEXT");
        assertThat(field(p, 12).plan().fillType()).isEqualTo("BARLEY");
        assertThat(field(p, 12).plan().note()).isEqualTo("Wintergerste");
        assertThat(field(p, 12).sown()).isTrue();
        assertThat(field(p, 12).lastCrop()).isEqualTo("WHEAT");
        assertThat(field(p, 14).plan().target()).isEqualTo("RUNNING");
        assertThat(field(p, 14).plan().sowPeriod()).isEqualTo(7);
        assertThat(p.windows()).isTrue();
        assertThat(p.reminders()).as("default on").isTrue();
        // one open plan per field: a new choice replaces it
        planner.plan(sg, 14, CropPlanService.FALLOW, null, null, null);
        assertThat(plans.findBySavegameAndStatus(sg, CropPlan.Status.OPEN)).hasSize(2);
        assertThat(field(planner.planning(sg), 14).plan().fruitType()).isEqualTo(CropPlanService.FALLOW);
        assertThatThrownBy(() -> planner.plan(sg, 99, "WHEAT", null, null, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> planner.plan(sg, 12, "RICE", null, null, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> planner.plan(sg, 12, "WHEAT", "CHAFF", null, null)).isInstanceOf(BusinessRuleException.class);
        planner.plan(sg, 12, "MAIZE", "CHAFF", null, null);
        planner.remove(sg, 12);
        assertThat(field(planner.planning(sg), 12).plan()).isNull();
    }

    @Test
    void theChecksAreHintsWithTheirFigures() {
        facts(true, "[{ \"fillType\": \"WHEAT\", \"amount\": 0, \"freeCapacity\": 1000 }]");
        planner.plan(sg, 12, "WHEAT", null, 9, null);
        FieldPlan f = field(planner.planning(sg), 12);
        assertThat(codes(f)).containsExactly("ROTATION_SAME", "ROTATION_CUT", "SOW_OUTSIDE_WINDOW", "TOO_LATE", "STORAGE",
                "FERTILIZER_CLOSED");
        assertThat(f.plan().checks().getFirst().values()).containsEntry("hectares", 4.5).containsEntry("premium", 180L);
        assertThat(f.plan().checks().get(1).values()).containsEntry("share", 50L);
        // the field without lime in the running season gets the soil hint, a fallow plan none
        planner.plan(sg, 14, "BARLEY", null, null, null);
        assertThat(codes(field(planner.planning(sg), 14))).contains("LIME").doesNotContain("ROTATION_SAME", "TOO_LATE");
        planner.plan(sg, 14, CropPlanService.FALLOW, null, null, null);
        assertThat(codes(field(planner.planning(sg), 14))).isEmpty();
    }

    @Test
    void theForecastNamesItsSources() {
        planner.plan(sg, 12, "WHEAT", null, null, null);
        planner.plan(sg, 14, "BARLEY", null, null, null);
        Planning p = planner.planning(sg);
        var wheat = field(p, 12).plan().forecast();
        assertThat(wheat.litersPerHa()).isCloseTo(8000, within(1e-6));
        assertThat(wheat.yieldSource()).isEqualTo("FIELD");
        assertThat(wheat.pricePerLiter()).isCloseTo(0.23, within(1e-9));
        assertThat(wheat.priceSource()).isEqualTo("BEST");
        assertThat(wheat.directCostsPerHa()).isCloseTo(900, within(1e-6)); // 1000 l seed x 0.9
        assertThat(wheat.costSource()).isEqualTo("SEED");
        assertThat(wheat.margin()).isCloseTo(36_000 * 0.23 - 4.5 * 900, within(1e-6));
        var barley = field(p, 14).plan().forecast();
        assertThat(barley.yieldSource()).isEqualTo("BASE");
        assertThat(barley.litersPerHa()).isCloseTo(7000, within(1e-6));
        // a wheat plan on the new field takes the average of the crop over all fields
        planner.plan(sg, 14, "WHEAT", null, null, null);
        assertThat(field(planner.planning(sg), 14).plan().forecast().yieldSource()).isEqualTo("CROP");
        // an open forward contract sets the price
        ForwardContract c = new ForwardContract();
        c.setSavegame(sg);
        c.setFillType("WHEAT");
        c.setSellPoint("Mill");
        c.setQuantity(20_000);
        c.setFixedPrice(250);
        c.setStatus(ForwardContract.OPEN);
        c.setDeliveryStartGameTime(t + GameTime.days(10));
        c.setDeadlineGameTime(t + GameTime.days(11));
        forwardContracts.save(c);
        p = planner.planning(sg);
        assertThat(field(p, 12).plan().forecast().priceSource()).isEqualTo("FORWARD");
        assertThat(field(p, 12).plan().forecast().pricePerLiter()).isCloseTo(0.25, within(1e-9));
        // A4: seed and the share sold ahead; "wie Vorjahr" compares
        assertThat(p.seeds()).singleElement().satisfies(s -> {
            assertThat(s.fruitType()).isEqualTo("WHEAT");
            assertThat(s.liters()).isCloseTo(6500, within(1e-6));
            assertThat(s.cost()).isCloseTo(5850, within(1e-6));
        });
        var market = p.marketing().getFirst();
        assertThat(market.expected()).isCloseTo(52_000, within(1e-6));
        assertThat(market.soldPercent()).isCloseTo(20_000 / 52_000.0 * 100, within(1e-6));
        assertThat(p.planMargin()).isNotNull();
        assertThat(field(p, 12).previous().fruitType()).isEqualTo("WHEAT");
        assertThat(p.feed()).as("no stables").isEmpty();
    }

    @Test
    void theSowingWindowBringsTaskCalendarLiquidityAndOneReminder() {
        planner.plan(sg, 14, "WHEAT", null, null, null);
        var due = planner.due(sg);
        assertThat(due).singleElement().satisfies(d -> {
            assertThat(d.start()).isEqualTo(GameTime.days(10));
            assertThat(d.end()).isEqualTo(GameTime.days(12));
        });
        var task = tasks.tasks(sg).items().stream().filter(x -> "SOW".equals(x.type())).findFirst().orElseThrow();
        assertThat(task.kind()).isEqualTo("WHEAT");
        assertThat(task.deadlineGameTime()).isEqualTo(GameTime.days(12));
        assertThat(task.sowDue().fieldName()).isEqualTo("14");
        assertThat(calendar.overview(sg).agenda()).anyMatch(a -> "SOW_WINDOW_END".equals(a.kind()) && "14".equals(a.title()));
        assertThat(planner.plannedByMonth(sg).get(10L)).singleElement()
                .satisfies(c -> assertThat(c.amount()).isEqualTo(-1800)); // 2 ha x 900
        // a maize plan waits for its window in February
        planner.plan(sg, 12, "MAIZE", null, null, null);
        assertThat(tasks.tasks(sg).items().stream().filter(x -> "SOW".equals(x.type()))).hasSize(1);
        // the window of field 14 ends with September: the cooperative reminds once
        t = GameTime.days(11);
        facts(true, "[]");
        planner.onMonth(new GameMonthPassedEvent(sg.getId(), 11, t));
        assertThat(count("CROP_PLAN_REMINDER")).isEqualTo(1);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("14 (WHEAT)");
        planner.onMonth(new GameMonthPassedEvent(sg.getId(), 11, t));
        assertThat(count("CROP_PLAN_REMINDER")).isEqualTo(1);
        planner.setReminders(sg, false);
        assertThat(planner.planning(sg).reminders()).isFalse();
    }

    @Test
    void theSeasonDecidesMatchedOrDeviatedAndPlanMeetsReality() {
        planner.plan(sg, 14, "BARLEY", null, null, null);
        planner.plan(sg, 12, "BARLEY", null, null, null);
        FieldBookEntry running14 = book.findBySavegameAndFarmlandIdOrderByIdAsc(sg, 14).getLast();
        running14.setFruitTypeAuto("WHEAT");
        running14.setFillTypeAuto("WHEAT");
        book.save(running14);
        t += GameTime.hours(1);
        facts(true, "[]");
        CropPlan p14 = plans.findBySavegameOrderByIdAsc(sg).stream().filter(p -> p.getFarmlandId() == 14).findFirst().orElseThrow();
        assertThat(p14.getStatus()).isEqualTo(CropPlan.Status.DEVIATED);
        assertThat(p14.getPlannedLitersPerHa()).isCloseTo(7000, within(1e-6));
        assertThat(p14.getPlannedYieldSource()).isEqualTo("BASE");
        // field 12: the running wheat is not the planned season; the next one with barley is
        FieldBookEntry running12 = book.findBySavegameAndFarmlandIdOrderByIdAsc(sg, 12).getLast();
        running12.setStatus(Status.HARVESTED);
        running12.setHarvestYear(2);
        running12.setLitersAuto(31_500.0);
        running12.setHectares(4.5);
        book.save(running12);
        entry(12, Status.RUNNING, "BARLEY", null, null, null, false);
        t += GameTime.hours(1);
        facts(true, "[]");
        assertThat(plans.findBySavegameOrderByIdAsc(sg)).extracting(CropPlan::getStatus)
                .containsOnly(CropPlan.Status.DEVIATED, CropPlan.Status.MATCHED);
        // the season of field 14 ends: plan / actual with the reached l/ha
        running14.setStatus(Status.HARVESTED);
        running14.setHarvestYear(2);
        running14.setLitersAuto(14_000.0);
        running14.setHectares(2.0);
        book.save(running14);
        var history = planner.planning(sg).history();
        var row14 = history.stream().filter(h -> h.farmlandId() == 14).findFirst().orElseThrow();
        assertThat(row14.status()).isEqualTo("DEVIATED");
        assertThat(row14.plannedCrop()).isEqualTo("BARLEY");
        assertThat(row14.actualCrop()).isEqualTo("WHEAT");
        assertThat(row14.ended()).isTrue();
        assertThat(row14.actualLitersPerHa()).isCloseTo(7000, within(1e-6));
        assertThat(history.stream().filter(h -> h.farmlandId() == 12).findFirst().orElseThrow().ended()).isFalse();
        // a rewind before the season began opens the plan again
        planner.onRewound(new BridgeEvents.Rewound(sg.getId(), t, t - GameTime.hours(3)));
        assertThat(plans.findBySavegameAndStatus(sg, CropPlan.Status.OPEN)).hasSize(2);
    }

    @Test
    void aFieldThatIsNoLongerOwnLosesItsPlan() {
        planner.plan(sg, 14, "WHEAT", null, null, null);
        t += GameTime.hours(1);
        facts(false, "[]");
        assertThat(plans.findBySavegameOrderByIdAsc(sg)).isEmpty();
    }

    @Test
    void theRotationSuggestionSkipsTheLastCropsAndTheCooperativeAnswersOncePerYear() {
        var suggestion = planner.suggest(sg, 12).orElseThrow();
        assertThat(suggestion.fruitType()).as("wheat stood there twice; barley has a price").isEqualTo("BARLEY");
        assertThat(suggestion.reason()).isEqualTo("YIELD");
        assertThatThrownBy(() -> planner.discuss(sg)).isInstanceOf(BusinessRuleException.class);
        planner.plan(sg, 12, "WHEAT", null, null, null);
        planner.discuss(sg);
        NarrationJob job = jobs.findBySavegameOrderByIdAsc(sg).getLast();
        assertThat(job.getEventType()).isEqualTo("CROP_PLAN_DISCUSSION");
        assertThat(job.getNotBeforeGameTime()).isEqualTo(GameTime.days(11) + GameTime.hours(9));
        assertThat(job.getFactsJson()).contains("\"plans\":1").contains("\"rotationFields\":1");
        assertThat(planner.planning(sg).discussed()).isTrue();
        assertThatThrownBy(() -> planner.discuss(sg)).isInstanceOf(BusinessRuleException.class);
    }
}
