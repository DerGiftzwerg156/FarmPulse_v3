package de.farmpulse.rpsim.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.FieldBookEntry.Status;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.field.FieldBookEvaluationService.Evaluation;
import de.farmpulse.rpsim.field.FieldBookEvaluationService.MeasureComparison;
import de.farmpulse.rpsim.repository.FieldBookEntryRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.3 section E: period (default 5, kept per savegame, 0 = all), table field x year, l/ha and the comparison
 * with / without a measure per crop and product (owner decisions 2026-10-08 / 2026-10-09).
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FieldBookEvaluationTest {

    @Autowired Fixtures fx;
    @Autowired FieldBookEvaluationService evaluation;
    @Autowired FieldBookEntryRepository entries;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setMarketContextJson("""
                { "savegameId": "%s", "mapName": "m", "sellPoints": [], "fillTypes": [], "farmlands": [],
                  "fruitTypes": [{ "name": "WHEAT", "fillType": "WHEAT", "needsRolling": true },
                                 { "name": "MAIZE", "fillType": "MAIZE", "needsRolling": true, "products": ["CHAFF"] },
                                 { "name": "GRASS", "fillType": "GRASS_WINDROW", "regrows": true,
                                   "needsRolling": false }] }""".formatted(sg.getBridgeSavegameId()));
        facts(10, "{ \"plowingRequired\": true, \"limeRequired\": true, \"weedsEnabled\": false, \"stonesEnabled\": true }");
    }

    /** Latest export: FS25 year and the soil rules; fields 12 and 13 are own fields. */
    private void facts(int year, String rules) {
        String extra = "\"calendar\": { \"period\": 1, \"dayInPeriod\": 1, \"daysPerPeriod\": 1, \"year\": " + year
                + ", \"monotonicDay\": 0 }, \"fields\": [" + field(12) + "," + field(13) + "]"
                + (rules == null ? "" : ", \"fieldRules\": " + rules);
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000,
                TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000), extra));
    }

    private static String field(int id) {
        return """
                { "farmlandId": %d, "name": "Feld %d", "hectares": 4, "growthState": 0, "weedState": 0, "stoneLevel": 0,
                  "sprayLevel": 0, "limeLevel": 0, "plowLevel": 1 }""".formatted(id, id);
    }

    private FieldBookEntry entry(int farmlandId, int year, String crop, String product, Double liters, boolean limed,
                                 int fert) {
        FieldBookEntry e = new FieldBookEntry();
        e.setSavegame(sg);
        e.setFarmlandId(farmlandId);
        e.setFieldName("Feld " + farmlandId);
        e.setStatus(liters == null && crop == null ? Status.NO_HARVEST : Status.HARVESTED);
        e.setHarvestYear(year);
        e.setHectares(4.0);
        e.setStartedGameTime(0);
        e.setEndedGameTime(1L);
        e.setFruitTypeAuto(crop);
        e.setFillTypeAuto(product);
        e.setLitersAuto(liters);
        e.setLimedAuto(limed);
        e.setFertCountAuto(fert);
        return entries.save(e);
    }

    private static MeasureComparison measure(Evaluation ev, String crop, String product, String measure) {
        return ev.comparisons().stream().filter(c -> c.fruitType().equals(crop) && product.equals(c.fillType()))
                .findFirst().orElseThrow().measures().stream().filter(m -> m.measure().equals(measure)).findFirst()
                .orElse(null);
    }

    @Test
    void theDefaultPeriodIsTheLastFiveHarvestYearsUpToTheCurrentYear() {
        entry(12, 5, "WHEAT", "WHEAT", 32000.0, false, 0); // outside (6..10)
        entry(12, 6, "WHEAT", "WHEAT", 36000.0, true, 2);
        entry(12, 10, "WHEAT", "WHEAT", 40000.0, true, 1);
        Evaluation ev = evaluation.evaluate(sg);
        assertThat(ev.years()).isEqualTo(5);
        assertThat(ev.defaultYears()).isEqualTo(5);
        assertThat(ev.fromYear()).isEqualTo(6);
        assertThat(ev.toYear()).isEqualTo(10);
        assertThat(ev.yearsAvailable()).isEqualTo(6);
        assertThat(ev.fields()).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("Feld 12");
            assertThat(r.cells()).extracting(FieldBookEvaluationService.Cell::harvestYear).containsExactly(6, 10);
            assertThat(r.cells().getFirst().litersPerHectare()).isEqualTo(9000.0);
            assertThat(r.cells().getFirst().measures()).containsEntry("LIMED", true).containsEntry("FERT2", true);
        });
    }

    @Test
    void theChosenPeriodIsKeptPerSavegameAndZeroMeansAllYears() {
        entry(12, 1, "WHEAT", "WHEAT", 30000.0, false, 0);
        entry(12, 10, "WHEAT", "WHEAT", 40000.0, false, 0);
        evaluation.choosePeriod(sg, 1);
        assertThat(sg.getFieldBookYears()).isEqualTo(1);
        assertThat(evaluation.evaluate(sg).fields().getFirst().cells()).hasSize(1);
        evaluation.choosePeriod(sg, 0);
        Evaluation all = evaluation.evaluate(sg);
        assertThat(all.fromYear()).isEqualTo(1);
        assertThat(all.fields().getFirst().cells()).hasSize(2);
        assertThatThrownBy(() -> evaluation.choosePeriod(sg, -1)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void runningAndHeldEntriesDoNotCountAndSoldFieldsStay() {
        FieldBookEntry held = entry(12, 9, "WHEAT", "WHEAT", 99000.0, true, 2);
        held.setHeld(true); // waits as a notice in a closed year
        entries.save(held);
        FieldBookEntry running = entry(12, 10, "WHEAT", "WHEAT", 1000.0, false, 0);
        running.setStatus(Status.RUNNING);
        running.setHarvestYear(null);
        entries.save(running);
        entry(20, 8, "WHEAT", "WHEAT", 28000.0, false, 0); // field 20 no longer own
        Evaluation ev = evaluation.evaluate(sg);
        assertThat(ev.fields()).singleElement().satisfies(r -> {
            assertThat(r.farmlandId()).isEqualTo(20);
            assertThat(r.name()).isEqualTo("Feld 20");
        });
    }

    @Test
    void comparisonPerCropAndProductWithAndWithoutEachMeasure() {
        entry(12, 7, "WHEAT", "WHEAT", 36000.0, true, 2); // 9000 l/ha
        entry(13, 7, "WHEAT", "WHEAT", 32000.0, true, 1); // 8000 l/ha
        entry(12, 8, "WHEAT", "WHEAT", 28000.0, false, 0); // 7000 l/ha
        entry(13, 8, "WHEAT", "WHEAT", null, false, 0); // no litres: counts nowhere
        entry(12, 9, "MAIZE", "CHAFF", 200000.0, true, 1);
        entry(13, 9, "MAIZE", "MAIZE", 40000.0, false, 1);
        Evaluation ev = evaluation.evaluate(sg);
        MeasureComparison limed = measure(ev, "WHEAT", "WHEAT", "LIMED");
        assertThat(limed.withAvg()).isCloseTo(8500.0, within(1e-9));
        assertThat(limed.withN()).isEqualTo(2);
        assertThat(limed.withoutAvg()).isCloseTo(7000.0, within(1e-9));
        assertThat(limed.withoutN()).isEqualTo(1);
        MeasureComparison fert2 = measure(ev, "WHEAT", "WHEAT", "FERT2");
        assertThat(List.of(fert2.withN(), fert2.withoutN())).containsExactly(1, 2);
        MeasureComparison mulched = measure(ev, "WHEAT", "WHEAT", "MULCHED");
        assertThat(mulched.withAvg()).as("n = 0: no average").isNull();
        assertThat(mulched.withN()).isZero();
        // weeds are off in the savegame: no weed comparison; grain maize and chaff are apart
        assertThat(measure(ev, "WHEAT", "WHEAT", "WEEDS")).isNull();
        assertThat(ev.comparisons()).extracting(c -> c.fruitType() + "/" + c.fillType())
                .containsExactly("MAIZE/CHAFF", "MAIZE/MAIZE", "WHEAT/WHEAT");
    }

    @Test
    void cropsThatAreNotRolledHaveNoRollingComparisonAndUnknownRulesShowLimeAndWeeds() {
        facts(10, null);
        entry(12, 9, "GRASS", "GRASS_WINDROW", 30000.0, false, 1);
        entry(13, 9, "WHEAT", "WHEAT", 36000.0, false, 1);
        Evaluation ev = evaluation.evaluate(sg);
        assertThat(measure(ev, "GRASS", "GRASS_WINDROW", "ROLLED")).isNull();
        assertThat(measure(ev, "WHEAT", "WHEAT", "ROLLED")).isNotNull();
        assertThat(ev.showLime()).isTrue();
        assertThat(ev.showWeeds()).isTrue();
        assertThat(measure(ev, "WHEAT", "WHEAT", "WEEDS")).isNotNull();
    }
}
