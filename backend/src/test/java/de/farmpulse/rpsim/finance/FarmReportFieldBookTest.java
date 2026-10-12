package de.farmpulse.rpsim.finance;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.diary.ChronicleService;
import de.farmpulse.rpsim.domain.FieldBookEntry;
import de.farmpulse.rpsim.domain.FieldBookEntry.Status;
import de.farmpulse.rpsim.domain.FieldCropHistory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.finance.FarmReportService.FieldLine;
import de.farmpulse.rpsim.finance.FarmReportService.Report;
import de.farmpulse.rpsim.repository.FieldBookEntryRepository;
import de.farmpulse.rpsim.repository.FieldCropHistoryRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap V3.3 R33-W1 (owner decisions 2026-10-12): the litres of the field book replace the estimate in the farm report
 * (and so in the chronicle and the annual review) where a harvested line of the crop history has the same field, year
 * and crop; the report says where the litres come from and names a product that is not the standard one.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FarmReportFieldBookTest {

    @Autowired Fixtures fx;
    @Autowired FarmReportService reports;
    @Autowired ChronicleService chronicle;
    @Autowired FactsService facts;
    @Autowired FieldCropHistoryRepository history;
    @Autowired FieldBookEntryRepository entries;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setMarketContextJson("""
                { "savegameId": "%s", "mapName": "m", "sellPoints": [], "fillTypes": [], "farmlands": [],
                  "fruitTypes": [{ "name": "WHEAT", "fillType": "WHEAT", "needsRolling": true },
                                 { "name": "MAIZE", "fillType": "MAIZE", "needsRolling": true, "products": ["CHAFF"] }] }"""
                .formatted(sg.getBridgeSavegameId()));
        fx.snapshot(sg, 100_000);
    }

    private void crop(int farmlandId, int year, String fruit, boolean harvested, Double estimate) {
        FieldCropHistory h = new FieldCropHistory();
        h.setSavegame(sg);
        h.setFarmlandId(farmlandId);
        h.setCropYear(year);
        h.setFruitType(fruit);
        h.setHarvested(harvested);
        h.setYieldLiters(estimate);
        history.save(h);
    }

    private FieldBookEntry book(int farmlandId, int year, String fruit, String product, double liters) {
        FieldBookEntry e = new FieldBookEntry();
        e.setSavegame(sg);
        e.setFarmlandId(farmlandId);
        e.setFieldName("Feld " + farmlandId);
        e.setStatus(Status.HARVESTED);
        e.setHarvestYear(year);
        e.setHectares(4.0);
        e.setStartedGameTime(0);
        e.setEndedGameTime(1L);
        e.setFruitTypeAuto(fruit);
        e.setFillTypeAuto(product);
        e.setLitersAuto(liters);
        return entries.save(e);
    }

    private Report report(int year) {
        return reports.build(sg, facts.latest(sg).orElseThrow(), year, 12L * year);
    }

    private static FieldLine line(Report r, int farmlandId) {
        return r.fields().stream().filter(f -> f.farmlandId() == farmlandId).findFirst().orElseThrow();
    }

    @Test
    void theLitresOfTheFieldBookReplaceTheEstimateAndSayWhereTheyComeFrom() {
        crop(12, 1, "WHEAT", true, 40_000.0);
        book(12, 1, "WHEAT", "WHEAT", 36_120.4);
        crop(13, 1, "WHEAT", true, 30_000.0);
        FieldBookEntry typed = book(13, 1, "WHEAT", "WHEAT", 31_000.0);
        typed.setLitersManual(28_000.0);
        entries.save(typed);
        crop(14, 1, "WHEAT", true, 25_000.0); // no field book litres: the estimate stays
        Report r = report(1);
        assertThat(line(r, 12)).satisfies(f -> {
            assertThat(f.yieldLiters()).isEqualTo(36_120L);
            assertThat(f.yieldSource()).isEqualTo(FieldLine.MEASURED);
            assertThat(f.product()).isNull();
        });
        assertThat(line(r, 13)).satisfies(f -> {
            assertThat(f.yieldLiters()).isEqualTo(28_000L);
            assertThat(f.yieldSource()).isEqualTo(FieldLine.ENTERED);
        });
        assertThat(line(r, 14)).satisfies(f -> {
            assertThat(f.yieldLiters()).isEqualTo(25_000L);
            assertThat(f.yieldSource()).isEqualTo(FieldLine.ESTIMATED);
        });
    }

    @Test
    void onlyAHarvestedLineOfTheSameFieldYearAndCropTakesTheLitres() {
        crop(12, 1, "WHEAT", false, null); // not harvested in the history: stays
        book(12, 1, "WHEAT", "WHEAT", 36_000.0);
        crop(13, 1, "WHEAT", true, 30_000.0);
        book(13, 1, "BARLEY", "BARLEY", 33_000.0); // another crop
        book(13, 2, "WHEAT", "WHEAT", 34_000.0); // another year
        crop(14, 1, "WHEAT", true, 20_000.0);
        FieldBookEntry held = book(14, 1, "WHEAT", "WHEAT", 99_000.0);
        held.setHeld(true); // waits as a notice in a closed year
        entries.save(held);
        book(15, 1, "WHEAT", "WHEAT", 10_000.0); // no line in the history: no new line
        Report r = report(1);
        assertThat(line(r, 12)).satisfies(f -> {
            assertThat(f.harvested()).isFalse();
            assertThat(f.yieldLiters()).isNull();
            assertThat(f.yieldSource()).isNull();
        });
        assertThat(line(r, 13).yieldSource()).isEqualTo(FieldLine.ESTIMATED);
        assertThat(line(r, 14).yieldLiters()).isEqualTo(20_000L);
        assertThat(r.fields()).extracting(FieldLine::farmlandId).doesNotContain(15);
    }

    @Test
    void anotherProductIsNamedInTheReportAndTheChronicle() {
        crop(12, 1, "MAIZE", true, 50_000.0);
        book(12, 1, "MAIZE", "CHAFF", 200_000.0);
        crop(13, 1, "MAIZE", true, 45_000.0);
        book(13, 1, "MAIZE", "MAIZE", 48_000.0);
        Report r = report(1);
        assertThat(line(r, 12).product()).isEqualTo("CHAFF");
        assertThat(line(r, 13).product()).isNull();
        reports.save(sg, r);
        String md = chronicle.markdown(sg);
        assertThat(md).contains("| Feld 12 | Mais (Häckselgut) |").contains("200.000 l (gemessen)");
        assertThat(md).contains("| Feld 13 | Mais |").contains("48.000 l (gemessen)");
    }

    @Test
    void aReportWrittenBeforeKeepsItsLinesWithoutASource() {
        FieldLine old = json.readValue("""
                { "farmlandId": 12, "fruitType": "WHEAT", "hectares": 4.5, "harvested": true, "withered": false,
                  "yieldLiters": 42750 }""", FieldLine.class);
        assertThat(old.yieldSource()).isNull();
        assertThat(old.product()).isNull();
        assertThat(old.yieldLiters()).isEqualTo(42_750L);
    }
}
