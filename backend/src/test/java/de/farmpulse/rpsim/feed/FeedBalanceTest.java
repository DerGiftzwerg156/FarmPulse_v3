package de.farmpulse.rpsim.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.HiringService;
import de.farmpulse.rpsim.feed.FeedBalanceService.Balance;
import de.farmpulse.rpsim.feed.FeedBalanceService.GroupBalance;
import de.farmpulse.rpsim.repository.FeedSnapshotRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.tablet.CalendarPlanService;
import de.farmpulse.rpsim.tablet.TaskService;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V4 section F: the daily need - estimated by the game, measured from the eaten feed (mod counter or sinking
 * trough), scaled with the herd - split into the food groups, the stock with entered bales that sink with the use, reach
 * and traffic light in game months, next harvest and shortfall, yearly need and costs, the warning of the animal keeper
 * at most once per game month and group, tasks and calendar (owner decisions 2026-10-13).
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FeedBalanceTest {

    @Autowired Fixtures fx;
    @Autowired FeedBalanceService feed;
    @Autowired FeedSnapshotRepository snapshots;
    @Autowired NarrationJobRepository jobs;
    @Autowired HiringService hiring;
    @Autowired TaskService tasks;
    @Autowired CalendarPlanService calendar;

    Savegame sg;
    long t;

    /** Cows eat Silage (0.5), Heu (0.3) and Kraftfutter (0.2); 3 days per game month, now day 10 = June (period 4). */
    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        t = sg.getCurrentGameTime();
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(3);
        sg.setCalPeriod(1);
        context("[]");
    }

    private void context(String fruitTypes) {
        sg.setMarketContextJson("""
                { "savegameId": "%s", "mapName": "m", "sellPoints": [], "fillTypes": [], "farmlands": [],
                  "fruitTypes": %s,
                  "fillTypeCosts": [{ "fillType": "SILAGE", "costPerLiter": 0.1 },
                                    { "fillType": "DRYGRASS_WINDROW", "costPerLiter": 0.2 }],
                  "animalFood": [{ "animalType": "COW", "mixtures": ["FORAGE"], "groups": [
                      { "title": "Silage", "productionWeight": 0.5, "fillTypes": ["SILAGE"] },
                      { "title": "Heu", "productionWeight": 0.3, "fillTypes": ["DRYGRASS_WINDROW"] },
                      { "title": "Kraftfutter", "productionWeight": 0.2, "fillTypes": ["MINERAL_FEED"] }] }] }"""
                .formatted(sg.getBridgeSavegameId(), fruitTypes));
    }

    /** One export of stable hus_00003 (24 cows). consumed = feedConsumed entries or null (no hook / older mod). */
    private void export(double litersPerHour, String consumed, String trough, double straw, String stock) {
        sg.setCurrentGameTime(t);
        String husbandry = """
                { "husbandryUniqueId": "hus_00003", "health": 90, "food": 0.5, "conditions": [],
                  "foodLitersPerHour": %s, "foodCapacity": 20000, "trough": [%s], "strawLitersPerHour": 10,
                  "straw": %s%s }""".formatted(litersPerHour, trough, straw,
                consumed == null ? "" : ", \"feedConsumed\": [" + consumed + "]");
        String doc = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                "\"husbandries\": [" + husbandry + "], \"feedStock\": [" + stock + "], \"tradeStorage\": "
                        + "[{ \"fillType\": \"DRYGRASS_WINDROW\", \"amount\": 1500, \"freeCapacity\": 10000 }]");
        var s = fx.snapshot(sg, t, 100_000, doc);
        feed.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private static String amount(String fillType, double liters) {
        return "{ \"fillType\": \"%s\", \"amount\": %s }".formatted(fillType, liters);
    }

    private static String eaten(double silage, double hay) {
        return "{ \"fillType\": \"SILAGE\", \"liters\": %s }, { \"fillType\": \"DRYGRASS_WINDROW\", \"liters\": %s }"
                .formatted(silage, hay);
    }

    private static final String TROUGH = amount("SILAGE", 1200) + ", " + amount("DRYGRASS_WINDROW", 600);
    private static final String STOCK = amount("SILAGE", 54000) + ", " + amount("DRYGRASS_WINDROW", 1500);

    private void day() {
        t += GameTime.days(1);
        sg.setCurrentGameTime(t);
        feed.onDay(new GameDayPassedEvent(sg.getId(), GameTime.dayIndex(t), t));
    }

    private static GroupBalance group(Balance b, String title) {
        return b.animals().getFirst().groups().stream().filter(g -> g.title().equals(title)).findFirst().orElseThrow();
    }

    private long mails() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType)
                .filter("FEED_SHORTAGE_WARNING"::equals).count();
    }

    @Test
    void beforeTheFirstMeasurementTheNeedOfTheGameIsSplitByProductionWeight() {
        export(50, null, TROUGH, 480, STOCK);
        Balance b = feed.balance(sg);
        assertThat(b.tracked()).isTrue();
        assertThat(b.counted()).isFalse();
        assertThat(b.daysPerPeriod()).isEqualTo(3);
        var stable = b.stables().getFirst();
        assertThat(stable.measured()).isFalse();
        assertThat(stable.needPerDay()).isEqualTo(1200); // 50 l per hour x 24
        assertThat(stable.troughHours()).isCloseTo(36, within(1e-9)); // 1800 l / 50 l per hour
        assertThat(stable.strawPerDay()).isEqualTo(240);
        assertThat(b.animals().getFirst().animals()).isEqualTo(24);

        GroupBalance silage = group(b, "Silage");
        assertThat(silage.needPerDay()).isEqualTo(600);
        assertThat(silage.estimated()).isTrue();
        assertThat(silage.reachDays()).isCloseTo(92, within(1e-9)); // (54000 + 1200) / 600
        assertThat(silage.reachMonths()).isCloseTo(30.67, within(0.01));
        assertThat(silage.status()).isEqualTo(FeedBalanceService.GREEN);
        assertThat(silage.yearNeed()).isEqualTo(21_600); // 600 x 12 x 3
        assertThat(silage.coveredPercent()).isCloseTo(255.6, within(0.1));
        assertThat(silage.monthlyCost()).isCloseTo(180, within(1e-9)); // 600 x 0.1 x 3
        assertThat(silage.neighborTrade()).isFalse();

        GroupBalance hay = group(b, "Heu");
        assertThat(hay.reachDays()).isCloseTo(2100 / 360.0, within(1e-9));
        assertThat(hay.status()).as("under 3 months").isEqualTo(FeedBalanceService.YELLOW);
        assertThat(hay.neighborTrade()).isTrue();
        assertThat(hay.emptyAt()).isEqualTo(t + GameTime.days(2100 / 360.0));

        GroupBalance mineral = group(b, "Kraftfutter");
        assertThat(mineral.reachDays()).isZero();
        assertThat(mineral.status()).isEqualTo(FeedBalanceService.RED);
        assertThat(mineral.monthlyCost()).isNull();

        GroupBalance straw = b.animals().getFirst().groups().getLast();
        assertThat(straw.kind()).isEqualTo(FeedBalanceService.BEDDING);
        assertThat(straw.trough()).isEqualTo(480);
        assertThat(straw.status()).isEqualTo(FeedBalanceService.RED); // 480 l = 2 days
        assertThat(stable.monthlyCost()).isCloseTo(180 + 216, within(1e-9));
        assertThat(b.worst().title()).isEqualTo("Kraftfutter");
        assertThat(b.worst().reachDays()).isZero();
        assertThat(silage.plannedHarvest()).as("without the plan").isNull();
        // F5: with the planned harvest of the crop plan the yearly need is compared with stock + plan
        GroupBalance planned = group(feed.balance(sg, java.util.Map.of("SILAGE", 20_000.0)), "Silage");
        assertThat(planned.plannedHarvest()).isEqualTo(20_000);
        assertThat(planned.coveredWithPlanPercent()).isCloseTo((55_200 + 20_000) / 21_600.0 * 100, within(1e-6));
        assertThat(planned.yearBalanceWithPlan()).isCloseTo(55_200 + 20_000 - 21_600, within(1e-6));
        assertThat(planned.coveredPercent()).as("the stock alone stays").isCloseTo(255.6, within(0.1));
    }

    @Test
    void theMeasuredNeedIsTheMeanOfTheLastDaysAndFollowsTheHerd() {
        export(50, eaten(1000, 500), TROUGH, 480, STOCK); // the first counter values are the base
        day();
        export(50, eaten(1800, 900), TROUGH, 480, STOCK);
        assertThat(feed.balance(sg).stables().getFirst().measured()).as("one day measured").isTrue();
        day();
        export(50, eaten(2600, 1300), TROUGH, 480, STOCK);
        Balance b = feed.balance(sg);
        assertThat(b.counted()).isTrue();
        var stable = b.stables().getFirst();
        assertThat(stable.needPerDay()).isCloseTo(1200, within(1e-6));
        assertThat(stable.needs()).extracting(FeedBalanceService.FillTypeNeed::fillType)
                .containsExactly("DRYGRASS_WINDROW", "SILAGE");
        assertThat(group(b, "Silage").needPerDay()).isCloseTo(800, within(1e-6));
        assertThat(group(b, "Silage").estimated()).isFalse();
        assertThat(group(b, "Heu").needPerDay()).isCloseTo(400, within(1e-6));
        assertThat(group(b, "Kraftfutter").status()).as("not eaten").isEqualTo(FeedBalanceService.UNUSED);
        assertThat(group(b, "Silage").monthlyCost()).isCloseTo(240, within(1e-6)); // 800 x 0.1 x 3
        // ten more cows: the need of the game doubles, the measured need follows at once
        export(100, eaten(2600, 1300), TROUGH, 480, STOCK);
        assertThat(feed.balance(sg).stables().getFirst().needPerDay()).isCloseTo(2400, within(1e-6));
    }

    @Test
    void anOlderModIsMeasuredByTheSinkingTroughAndAFallingCounterIsTakenBack() {
        export(50, null, amount("SILAGE", 5000), 480, STOCK);
        day();
        export(50, null, amount("SILAGE", 4200), 480, STOCK);
        day();
        export(50, null, amount("SILAGE", 3400), 480, STOCK);
        Balance b = feed.balance(sg);
        assertThat(b.counted()).isFalse();
        assertThat(b.stables().getFirst().measured()).isTrue();
        assertThat(group(b, "Silage").needPerDay()).isCloseTo(800, within(1e-6));
    }

    @Test
    void enteredBalesSinkWhenTheyGoIntoTheStableAndAskForACheck() {
        feed.addManual(sg, "STRAW", 20, "ROUND_125", null, "Halle 2");
        export(50, null, TROUGH, 480, STOCK + ", " + amount("STRAW", 8000));
        Balance b = feed.balance(sg);
        var entry = b.manual().getFirst();
        assertThat(entry.liters()).isEqualTo(80_000);
        assertThat(entry.balesLeft()).isEqualTo(20);
        GroupBalance straw = b.animals().getFirst().groups().getLast();
        assertThat(straw.manual()).isEqualTo(80_000);
        assertThat(straw.status()).isEqualTo(FeedBalanceService.GREEN); // (88000 + 480) / 240 days
        // a bale goes into the stable: the straw rises, the silo does not fall
        t += GameTime.hours(1);
        export(50, null, TROUGH, 4480, STOCK + ", " + amount("STRAW", 8000));
        assertThat(feed.balance(sg).manual().getFirst().balesLeft()).isEqualTo(19);
        // straw from the silo: the entered stock stays
        t += GameTime.hours(1);
        export(50, null, TROUGH, 8480, STOCK + ", " + amount("STRAW", 4000));
        assertThat(feed.balance(sg).manual().getFirst().liters()).isEqualTo(76_000);
        // ten game days without a change ask "bitte prüfen"; "stimmt noch" starts again
        t += GameTime.days(10);
        sg.setCurrentGameTime(t);
        assertThat(feed.balance(sg).manual().getFirst().stale()).isTrue();
        long id = feed.balance(sg).manual().getFirst().id();
        feed.checkManual(sg, id);
        assertThat(feed.balance(sg).manual().getFirst().stale()).isFalse();
        feed.updateManual(sg, id, "STRAW", null, null, 12_000.0, null);
        assertThat(feed.balance(sg).manual().getFirst().bales()).isNull();
        assertThatThrownBy(() -> feed.addManual(sg, "STRAW", 3, "HUGE", null, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> feed.addManual(sg, "HAY", null, null, -5.0, null))
                .isInstanceOf(BusinessRuleException.class);
        feed.removeManual(sg, id);
        assertThat(feed.balance(sg).manual()).isEmpty();
    }

    @Test
    void aRedGroupWarnsOncePerGameMonthFromTheKeeperOrTheCooperative() {
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Raiffeisen Erlengrund");
        export(50, null, TROUGH, 480, STOCK + ", " + amount("STRAW", 90_000));
        day(); // day 11, month 3
        assertThat(mails()).isEqualTo(1); // Kraftfutter
        var job = jobs.findBySavegameOrderByIdAsc(sg).getLast();
        assertThat(job.getFactsJson()).contains("\"groupTitle\":\"Kraftfutter\"").contains("\"needPerDay\":240");
        assertThat(job.getCategory().name()).isEqualTo("LIVESTOCK");
        day(); // day 12 = month 4
        assertThat(mails()).isEqualTo(2);
        feed.warn(sg);
        assertThat(mails()).as("once per month").isEqualTo(2);
        hiring.createEmployee(sg, fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Jonas Hahn"),
                JobRole.ANIMAL_KEEPER, 70, 2200);
        t += GameTime.days(3); // month 5
        sg.setCurrentGameTime(t);
        feed.warn(sg);
        assertThat(mails()).isEqualTo(3);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getCategory().name()).isEqualTo("EMPLOYEE");
    }

    @Test
    void yellowAndRedGroupsAreTasksAndTheirEmptyDayIsInTheCalendar() {
        export(50, null, TROUGH, 480, STOCK + ", " + amount("STRAW", 90_000));
        var feedTasks = tasks.tasks(sg).items().stream().filter(x -> "FEED".equals(x.type())).toList();
        assertThat(feedTasks).extracting(x -> x.feedDue().title()).containsExactly("Kraftfutter", "Heu");
        assertThat(feedTasks.getLast().kind()).isEqualTo("YELLOW");
        assertThat(feedTasks.getLast().deadlineGameTime()).isEqualTo(t + GameTime.days(2100 / 360.0));
        assertThat(calendar.overview(sg).agenda()).anyMatch(a -> "FEED_EMPTY".equals(a.kind()) && "Heu".equals(a.title())
                && "DRYGRASS_WINDROW".equals(a.reference()));
    }

    @Test
    void theShortfallUntilTheNextHarvestOfTheCrop() {
        context("[{ \"name\": \"GRASS\", \"fillType\": \"GRASS_WINDROW\", \"harvestablePeriods\": [9] }]");
        export(50, null, TROUGH, 480, STOCK);
        GroupBalance hay = group(feed.balance(sg), "Heu");
        // period 9 starts in month 8 = day 24, 14 days from now: 360 x 14 - 2100
        assertThat(hay.nextHarvestAt()).isEqualTo(GameTime.days(24));
        assertThat(hay.shortfall()).isCloseTo(2940, within(1e-6));
        assertThat(group(feed.balance(sg), "Kraftfutter").nextHarvestAt()).isNull();
    }

    @Test
    void anOlderModShowsNoBalanceAndARewindDropsTheLaterSnapshots() {
        sg.setCurrentGameTime(t);
        var s = fx.snapshot(sg, t, 100_000, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                "\"husbandries\": [{ \"husbandryUniqueId\": \"hus_00003\", \"health\": 90, \"food\": 0.5 }]"));
        feed.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
        assertThat(feed.balance(sg).tracked()).isFalse();
        assertThat(feed.due(sg)).isEmpty();

        long start = t;
        export(50, eaten(1000, 500), TROUGH, 480, STOCK);
        day();
        assertThat(snapshots.findBySavegameOrderByGameTimeAsc(sg)).hasSize(2);
        feed.onRewound(new BridgeEvents.Rewound(sg.getId(), t, start));
        assertThat(snapshots.findBySavegameOrderByGameTimeAsc(sg)).hasSize(1);
    }
}
