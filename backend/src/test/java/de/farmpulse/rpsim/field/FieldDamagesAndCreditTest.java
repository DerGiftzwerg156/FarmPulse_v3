package de.farmpulse.rpsim.field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.HuntingService;
import de.farmpulse.rpsim.contract.InsuranceService;
import de.farmpulse.rpsim.credit.CreditScoringService;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.RainPeriod;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.RainPeriodRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V2 R2-C3 / R2-C5: hail and wild boars hit standing crops only, the bank counts the standing crops. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FieldDamagesAndCreditTest {

    @Autowired Fixtures fx;
    @Autowired InsuranceService insurance;
    @Autowired HuntingService hunting;
    @Autowired CreditScoringService scoring;
    @Autowired NarrationJobRepository jobs;
    @Autowired RpsimProperties props;
    @Autowired GameTime gameTime;
    @Autowired JsonMapper json;
    @Autowired RainPeriodRepository rain;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Otto Wendler");
        props.getFormulas().getInsurance().setHailDamageShareMin(0.1);
        props.getFormulas().getInsurance().setHailDamageShareMax(0.1);
        props.getFormulas().getHunting().setDamagePerHectareMin(400);
        props.getFormulas().getHunting().setDamagePerHectareMax(400);
    }

    @AfterEach
    void restore() {
        var i = new RpsimProperties.Insurance();
        props.getFormulas().getInsurance().setHailDamageShareMin(i.getHailDamageShareMin());
        props.getFormulas().getInsurance().setHailDamageShareMax(i.getHailDamageShareMax());
        var h = new RpsimProperties.Hunting();
        props.getFormulas().getHunting().setDamagePerHectareMin(h.getDamagePerHectareMin());
        props.getFormulas().getHunting().setDamagePerHectareMax(h.getDamagePerHectareMax());
        props.getFormulas().getFields().getYieldLitersPerSqm().clear();
    }

    private FarmFacts facts(String... fieldDocs) {
        String doc = FieldDocs.doc(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 1, FieldDocs.ALL_RULES, null,
                fieldDocs);
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, doc);
        return json.readValue(doc, FarmFacts.class);
    }

    private NarrationJob job(String type) {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().filter(j -> j.getEventType().equals(type)).findFirst()
                .orElseThrow();
    }

    @Test
    void hailNeverHitsAFieldWithoutAStandingCrop() {
        facts(FieldDocs.field(12, null, 0, null, null, 0, 0, 1, 1),
                FieldDocs.field(13, "WHEAT", 9, false, true, 0, 0, 1, 1),
                FieldDocs.field(14, "WHEAT", 10, true, false, 0, 0, 1, 1));
        assertThat(insurance.hail(sg)).isEmpty();
    }

    @Test
    void hailDamageIsTheHarvestValueTimesTheDamageShareAndNamesFieldAndCrop() {
        facts(FieldDocs.field(12, null, 0, null, null, 0, 0, 1, 1), FieldDocs.growing("WHEAT", 4));
        ServiceCase sc = insurance.hail(sg).orElseThrow();
        // 4.5 ha x 10,000 m² x 0.9 l/m² = 40,500 l x best price 230 €/1000 l = 9,315 € x 10 % = 931.5 -> 930
        assertThat(sc.getDamageAmount()).isEqualTo(930);
        assertThat(sc.getFarmlandId()).isEqualTo(12);
        assertThat(job("DAMAGE_NOTICE").getFactsJson()).contains("\"fieldName\":\"12\"", "\"fruitType\":\"WHEAT\"",
                "\"cropNote\":\", Weizen\"");
    }

    @Test
    void withoutYieldOrPriceHailFallsBackToThePerHectareRangeAndWithoutFieldsToV1() {
        facts(FieldDocs.field(12, "POTATO", 4, false, false, 0, 0, 1, 1)); // no price for potatoes
        long damage = insurance.hail(sg).orElseThrow().getDamageAmount();
        assertThat(damage).isBetween(900L, 4050L); // 4.5 ha x 200..900 €/ha
        fx.snapshot(sg, 100_000); // older mod: no field block, any own farmland
        assertThat(insurance.hail(sg)).isPresent();
    }

    @Test
    void aRainyMonthRaisesTheHailProbability() {
        long now = sg.getCurrentGameTime();
        assertThat(insurance.hailProbability(sg, now)).isCloseTo(0.2, within(1e-9));
        RainPeriod rp = new RainPeriod();
        rp.setSavegame(sg);
        rp.setMonthIndex(gameTime.monthIndex(sg, now) - 1);
        rp.setObservedMs(GameTime.hours(10));
        rp.setRainMs(GameTime.hours(5));
        rain.save(rp);
        // 0.2 x (1 + 1.0 x 0.5)
        assertThat(insurance.hailProbability(sg, now)).isCloseTo(0.3, within(1e-9));
    }

    @Test
    void wildBoarsOnlyHitConfiguredCropsAndScaleWithTheGrowth() {
        facts(FieldDocs.field(12, "CANOLA", 4, false, false, 0, 0, 1, 1));
        assertThat(hunting.damage(sg)).as("canola is not on the list").isEmpty();
        facts(FieldDocs.growing("MAIZE", 4));
        ServiceCase sc = hunting.damage(sg).orElseThrow();
        // 4.5 ha x 400 €/ha x growth 4/8
        assertThat(sc.getDamageAmount()).isEqualTo(900);
        assertThat(job("WILDLIFE_DAMAGE_REPORTED").getFactsJson()).contains("\"cropNote\":\" (Mais)\"");
    }

    @Test
    void theBankCountsStandingCropsWithTheDiscount() {
        var cfg = props.getFormulas().getCredit();
        FarmFacts f = facts(FieldDocs.growing("WHEAT", 4), FieldDocs.field(13, "WHEAT", 10, true, false, 0, 0, 1, 1));
        // 9,315 € harvest value x growth 0.5 x discount 0.5; the withered field counts nothing
        assertThat(scoring.standingCropValue(f, cfg)).isCloseTo(2328.75, within(1e-6));
        double withCrops = scoring.inputs(sg, 10_000, 12, 0.05).totalAssets();
        fx.snapshot(sg, sg.getCurrentGameTime() + 1, 100_000, TestData.farmFacts(sg.getBridgeSavegameId(),
                sg.getCurrentGameTime() + 1, 100_000));
        assertThat(withCrops - scoring.inputs(sg, 10_000, 12, 0.05).totalAssets()).isCloseTo(2328.75, within(1e-6));
    }

    @Test
    void anOlderModWithoutLitersPerSqmUsesTheConfiguredYield() {
        props.getFormulas().getFields().getYieldLitersPerSqm().put("WHEAT", 0.5);
        String doc = FieldDocs.growing("WHEAT", 8).replace("\"litersPerSqm\": 0.9, ", "");
        FarmFacts f = facts(doc);
        // 45,000 m² x 0.5 l/m² x 230 €/1000 l = 5,175 € x progress 1 x 0.5
        assertThat(scoring.standingCropValue(f, props.getFormulas().getCredit())).isCloseTo(2587.5, within(1e-6));
    }
}
