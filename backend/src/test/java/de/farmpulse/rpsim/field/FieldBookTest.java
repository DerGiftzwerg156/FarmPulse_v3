package de.farmpulse.rpsim.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.FieldBookEntry.Status;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.field.FieldBookService.Value;
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
 * Roadmap V3.3 section F: seasons (F1), measures (F2), litres (F3), corrections (F4) and closed years (F5) with the owner
 * decisions of 2026-10-08 / 2026-10-09.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FieldBookTest {

    @Autowired Fixtures fx;
    @Autowired FieldBookService book;

    Savegame sg;
    long t;

    /** Field state of farmland 12 (4.5 ha); crop null = no crop. Ripe at growth state 8. */
    record F(String crop, int gs, Boolean cut, int spray, int lime, Integer roller, int weed, Integer stubble,
             String sprayType) {
        static F of(String crop, int gs) {
            return new F(crop, gs, crop == null ? null : false, 0, 0, null, 0, null, null);
        }

        F harvested() { return new F(crop, gs, true, spray, lime, roller, weed, stubble, sprayType); }
        F sprayed(int s, String kind) { return new F(crop, gs, cut, s, lime, roller, weed, stubble, kind); }
        F limed(int l) { return new F(crop, gs, cut, spray, l, roller, weed, stubble, sprayType); }
        F rolling(int r) { return new F(crop, gs, cut, spray, lime, r, weed, stubble, sprayType); }
        F weeds(int w) { return new F(crop, gs, cut, spray, lime, roller, w, stubble, sprayType); }
        F mulch(int s) { return new F(crop, gs, cut, spray, lime, roller, weed, s, sprayType); }

        String json(int farmlandId) {
            String cropPart = crop == null ? "" : """
                    "fruitType": "%s", "minHarvestingGrowthState": 8, "maxHarvestingGrowthState": 8, "fillType": "%s",
                    "withered": false, "cut": %s, """.formatted(crop, crop.equals("GRASS") ? "GRASS_WINDROW" : crop, cut);
            return """
                    { "farmlandId": %d, "name": "%d", "hectares": 4.5, %s"growthState": %d, "weedState": %d,
                      "stoneLevel": 0, "sprayLevel": %d, "limeLevel": %d, "plowLevel": 1%s%s%s }""".formatted(farmlandId,
                    farmlandId, cropPart, gs, weed, spray, lime, roller == null ? "" : ", \"rollerLevel\": " + roller,
                    stubble == null ? "" : ", \"stubbleShredLevel\": " + stubble,
                    sprayType == null ? "" : ", \"sprayType\": \"" + sprayType + "\"");
        }
    }

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        t = sg.getCurrentGameTime();
        sg.setMarketContextJson("""
                { "savegameId": "%s", "mapName": "m", "sellPoints": [], "fillTypes": [], "farmlands": [],
                  "fruitTypes": [{ "name": "WHEAT", "fillType": "WHEAT", "regrows": false, "needsRolling": true },
                                 { "name": "BARLEY", "fillType": "BARLEY", "regrows": false, "needsRolling": true },
                                 { "name": "GRASS", "fillType": "GRASS_WINDROW", "regrows": true, "needsRolling": false },
                                 { "name": "MAIZE", "fillType": "MAIZE", "regrows": false, "needsRolling": true,
                                   "products": ["CHAFF"] }] }""".formatted(sg.getBridgeSavegameId()));
    }

    /** One export with field 12 (and more fields as "id:json" pairs are not needed here) and optional counters. */
    private void export(int year, F field, String harvests) {
        export(year, field == null ? "" : field.json(12), harvests);
    }

    private void export(int year, String fieldsJson, String harvests) {
        t += GameTime.hours(1);
        sg.setCurrentGameTime(t);
        String extra = "\"calendar\": { \"period\": 1, \"dayInPeriod\": 1, \"daysPerPeriod\": 1, \"year\": " + year
                + ", \"monotonicDay\": " + t / 86_400_000L + " }, \"fields\": [" + fieldsJson + "]"
                + (harvests == null ? "" : ", \"harvests\": [" + harvests + "]");
        var s = fx.snapshot(sg, t, 100_000, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                extra));
        book.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private static String counter(String fruit, String fill, double liters) {
        return "{ \"farmlandId\": 12, \"fruitType\": \"%s\", \"fillType\": \"%s\", \"liters\": %s }".formatted(fruit, fill,
                liters);
    }

    private FieldBookEntry running() {
        return book.all(sg).stream().filter(e -> e.getStatus() == Status.RUNNING).findFirst().orElseThrow();
    }

    private List<FieldBookEntry> finished() {
        return book.all(sg).stream().filter(e -> e.getStatus() != Status.RUNNING).toList();
    }

    @Test
    void theFirstEntryCountsTheLevelsFoundAsDone() {
        export(1, F.of("WHEAT", 4).sprayed(1, "FERTILIZER").limed(1).rolling(0).mulch(0), null);
        FieldBookEntry e = running();
        assertThat(e.isFirstEntry()).isTrue();
        assertThat(e.fruitType()).isEqualTo("WHEAT");
        assertThat(e.fert1()).isTrue();
        assertThat(e.fert2()).isFalse();
        assertThat(e.limed()).isTrue();
        assertThat(e.rolled()).as("rolled crop that needs rolling").isTrue();
        assertThat(e.mulched()).isFalse();
        assertThat(FieldBookService.sprayTypes(e)).containsExactly("FERTILIZER");
    }

    @Test
    void aSeasonFromSowingToHarvestDetectsEveryMeasureAndEndsWithTheHarvestYear() {
        export(1, F.of(null, 0).rolling(0).mulch(0), null); // first entry: empty field, nothing done
        assertThat(running().fert1()).isFalse();
        export(1, F.of(null, 0).rolling(0).mulch(0).limed(1), null);
        export(1, F.of("WHEAT", 1).limed(1).rolling(1).mulch(0), null);
        export(1, F.of("WHEAT", 2).limed(1).rolling(0).mulch(0), null);
        export(1, F.of("WHEAT", 3).limed(1).rolling(0).mulch(0).sprayed(1, "LIQUID_MANURE"), null);
        export(1, F.of("WHEAT", 5).limed(1).rolling(0).mulch(0).sprayed(2, "FERTILIZER").weeds(3), null);
        export(1, F.of("WHEAT", 6).limed(1).rolling(0).mulch(0).sprayed(2, "FERTILIZER").weeds(1), null);
        export(2, F.of("WHEAT", 8).limed(1).rolling(0).mulch(0).sprayed(2, "FERTILIZER"),
                counter("WHEAT", "WHEAT", 1000));
        export(2, F.of("WHEAT", 9).harvested().limed(1).rolling(0).mulch(0).sprayed(0, null), counter("WHEAT", "WHEAT", 40000));
        // the litres of the last export come after the end: they go to the harvest of the year (rule 2)
        export(2, F.of("WHEAT", 9).harvested().limed(1).rolling(0).mulch(1).sprayed(0, null), counter("WHEAT", "WHEAT", 41000));
        FieldBookEntry h = finished().getFirst();
        assertThat(h.getStatus()).isEqualTo(Status.HARVESTED);
        assertThat(h.getHarvestYear()).as("FS25 year of the harvest").isEqualTo(2);
        assertThat(h.getHectares()).isEqualTo(4.5);
        assertThat(List.of(h.fert1(), h.fert2(), h.limed(), h.rolled(), h.weeds(), h.mulched()))
                .containsExactly(true, true, true, true, true, false);
        assertThat(FieldBookService.sprayTypes(h)).containsExactly("LIQUID_MANURE", "FERTILIZER");
        assertThat(h.liters()).isEqualTo(41000.0);
        // mulching the stubble belongs to the next season
        FieldBookEntry next = running();
        assertThat(next.mulched()).isTrue();
        assertThat(next.fruitType()).as("stubble is no crop").isNull();
        assertThat(next.isFirstEntry()).isFalse();
    }

    @Test
    void cutsOfAGrassFieldInOneYearAreOneEntry() {
        export(1, F.of("GRASS", 8), counter("GRASS", "GRASS_WINDROW", 0));
        export(1, F.of("GRASS", 3), counter("GRASS", "GRASS_WINDROW", 15000)); // cut back: regrows
        export(1, F.of("GRASS", 8), counter("GRASS", "GRASS_WINDROW", 15000));
        export(1, F.of("GRASS", 3).sprayed(1, "LIQUID_MANURE"), counter("GRASS", "GRASS_WINDROW", 27000));
        assertThat(finished()).singleElement().satisfies(e -> {
            assertThat(e.getHarvestYear()).isEqualTo(1);
            assertThat(e.liters()).isEqualTo(27000.0);
        });
        export(2, F.of("GRASS", 8).sprayed(1, null), counter("GRASS", "GRASS_WINDROW", 27000));
        export(2, F.of("GRASS", 3), counter("GRASS", "GRASS_WINDROW", 36000));
        assertThat(finished()).extracting(FieldBookEntry::getHarvestYear).containsExactly(1, 2);
    }

    @Test
    void anotherCropInTheSameYearIsNotKeptAndAHarvestBeatsNoHarvest() {
        export(1, F.of("WHEAT", 8), null);
        export(1, F.of("WHEAT", 9).harvested(), null);
        export(1, F.of("BARLEY", 2), null);
        export(1, F.of("BARLEY", 8), null);
        export(1, F.of("BARLEY", 9).harvested(), null);
        assertThat(finished()).as("only the main crop counts").singleElement()
                .satisfies(e -> assertThat(e.fruitType()).isEqualTo("WHEAT"));
        // year 2: wheat replaced without harvest (no harvest), then barley harvested: the harvest wins
        export(2, F.of("WHEAT", 4), null);
        export(2, F.of("BARLEY", 1), null);
        assertThat(finished()).extracting(FieldBookEntry::getStatus).containsExactly(Status.HARVESTED, Status.NO_HARVEST);
        export(2, F.of("BARLEY", 8), null);
        export(2, F.of("BARLEY", 9).harvested(), null);
        assertThat(finished()).filteredOn(e -> e.getHarvestYear() == 2).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(Status.HARVESTED);
            assertThat(e.fruitType()).isEqualTo("BARLEY");
        });
    }

    @Test
    void litresOfAProductAreKeptApartAndAFallingCounterIsTakenBack() {
        export(1, F.of("MAIZE", 6), counter("MAIZE", "CHAFF", 0));
        export(1, F.of("MAIZE", 8), counter("MAIZE", "CHAFF", 30000));
        FieldBookEntry r = running();
        assertThat(r.liters()).isEqualTo(30000.0);
        assertThat(r.fillType()).as("the product the machines harvested").isEqualTo("CHAFF");
        export(1, F.of("MAIZE", 8), counter("MAIZE", "CHAFF", 10000));
        assertThat(running().liters()).as("older savegame loaded: litres taken back").isEqualTo(10000.0);
    }

    @Test
    void aSoldFieldDropsItsRunningSeasonAndKeepsItsYears() {
        export(1, F.of("WHEAT", 8), null);
        export(1, F.of("WHEAT", 9).harvested(), null);
        export(1, "", null); // field gone from the export
        assertThat(book.all(sg)).singleElement().satisfies(e -> assertThat(e.getStatus()).isEqualTo(Status.HARVESTED));
    }

    @Test
    void aCorrectionWinsAndCanBeGivenBack() {
        export(1, F.of("WHEAT", 4).limed(0), null);
        long id = running().getId();
        book.setValue(sg, id, Value.LIMED, "true");
        book.setValue(sg, id, Value.LITERS, "52000");
        book.setValue(sg, id, Value.FRUIT_TYPE, "BARLEY");
        export(1, F.of("WHEAT", 5).limed(0), null);
        FieldBookEntry e = running();
        assertThat(e.limed()).isTrue();
        assertThat(e.liters()).isEqualTo(52000.0);
        assertThat(e.fruitType()).isEqualTo("BARLEY");
        assertThat(e.getFruitTypeAuto()).as("the detection goes on underneath").isEqualTo("WHEAT");
        book.setValue(sg, id, Value.LIMED, null);
        assertThat(running().limed()).as("given back to the detection").isFalse();
        assertThatThrownBy(() -> book.setValue(sg, id, Value.LITERS, "-1")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void harvestButtonEndsTheRunningSeasonWithTheCurrentYear() {
        export(3, F.of("POTATO", 5), null);
        book.harvestNow(sg, 12);
        assertThat(finished()).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(Status.HARVESTED);
            assertThat(e.getHarvestYear()).isEqualTo(3);
            assertThat(e.fruitType()).isEqualTo("POTATO");
        });
        assertThat(running().fruitType()).as("the next season starts with the field as it is").isEqualTo("POTATO");
    }

    @Test
    void aClosedYearIsLockedAndTakesLateDetectionsWhenReopened() {
        export(1, F.of("WHEAT", 8), counter("WHEAT", "WHEAT", 0));
        export(1, F.of("WHEAT", 9).harvested(), counter("WHEAT", "WHEAT", 30000));
        long id = finished().getFirst().getId();
        book.closeYear(sg, 1);
        assertThatThrownBy(() -> book.setValue(sg, id, Value.LIMED, "true")).isInstanceOf(BusinessRuleException.class);
        export(1, F.of("WHEAT", 9).harvested(), counter("WHEAT", "WHEAT", 32000)); // late litres
        assertThat(book.all(sg).stream().filter(e -> e.getId() == id).findFirst().orElseThrow().liters())
                .isEqualTo(30000.0);
        assertThat(book.notices(sg)).singleElement().satisfies(n -> assertThat(n.getLiters()).isEqualTo(2000.0));
        book.reopenYear(sg, 1);
        assertThat(book.notices(sg)).isEmpty();
        assertThat(book.all(sg).stream().filter(e -> e.getId() == id).findFirst().orElseThrow().liters())
                .isEqualTo(32000.0);
        assertThat(book.closedYears(sg)).isEmpty();
    }

    @Test
    void aRewindReopensTheHarvestedEntry() {
        export(1, F.of("WHEAT", 8), null);
        long before = t;
        export(1, F.of("WHEAT", 9).harvested(), null);
        export(1, F.of("WHEAT", 9).harvested().mulch(1), null);
        book.onRewound(new BridgeEvents.Rewound(sg.getId(), t, before));
        assertThat(book.all(sg)).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(Status.RUNNING);
            assertThat(e.getHarvestYear()).isNull();
            assertThat(e.fruitType()).isEqualTo("WHEAT");
        });
    }

    @Test
    void cropsFallBackToTheKnownCropsWithoutTheMapList() {
        sg.setMarketContextJson(null);
        export(1, F.of("SPELT", 4), null);
        assertThat(book.crops(sg)).extracting(FieldBookService.Crop::name).contains("SPELT", "WHEAT", "MAIZE");
    }
}
