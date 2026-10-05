package de.farmpulse.rpsim.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V3.1 R31-B3: fertiliser rules (owner decisions 2026-10-05). 30 game days per FS25 period. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class FertilizerRulesTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired FertilizerRulesService rules;
    @Autowired FieldService fields;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired ServiceCaseRepository cases;
    @Autowired PublicActionEventRepository publicActions;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(sg.getCurrentGameTime());
        sg.setCalDaysPerPeriod(30); // inspections are decided within the month
        sg.setCalPeriod(9); // November: closed period
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Frau Kessler");
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Genossenschaft Erlengrund");
    }

    @AfterEach
    void restore() {
        props.getFormulas().setFertilizerRules(new RpsimProperties.FertilizerRules());
    }

    static String field(int farmland, String fruit, String sprayType, int sprayLevel) {
        return """
                { "farmlandId": %d, "name": "%d", "hectares": 4.5, %s"growthState": 0, "weedState": 0, "stoneLevel": 0,
                  "sprayLevel": %d, %s"limeLevel": 1, "plowLevel": 1 }""".formatted(farmland, farmland,
                fruit == null ? "" : "\"fruitType\": \"" + fruit + "\", \"minHarvestingGrowthState\": 8, "
                        + "\"maxHarvestingGrowthState\": 8, ",
                sprayLevel, sprayType == null ? "" : "\"sprayType\": \"" + sprayType + "\", ");
    }

    private void facts(String extra, String... fieldDocs) {
        long t = sg.getCurrentGameTime();
        String doc = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                "\"calendar\": { \"period\": 9, \"dayInPeriod\": 1, \"daysPerPeriod\": 30, \"year\": 1, \"monotonicDay\": "
                        + t / DAY + " }, \"fields\": [" + String.join(",", fieldDocs) + "]" + (extra == null ? "" : ", " + extra));
        FactsSnapshot s = fx.snapshot(sg, t, 100_000, doc);
        fields.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
        rules.onFacts(new BridgeEvents.FactsIngested(sg.getId(), s.getId(), t, false));
    }

    private void advance(long ms) {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + ms);
    }

    private void day() {
        rules.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private List<ServiceCase> inspections() {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.AUTHORITY_INSPECTION));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    @Test
    void slurryInTheClosedPeriodFirstWarnsThenFines() {
        facts(null, field(12, null, "NONE", 0), field(13, null, "NONE", 0));
        advance(DAY / 10);
        facts(null, field(12, null, "LIQUID_MANURE", 1), field(13, null, "NONE", 0));
        ServiceCase first = inspections().getFirst();
        assertThat(first.getTitle()).isEqualTo(FertilizerRulesService.RULE);
        assertThat(first.getReference()).isEqualTo("12");
        assertThat(first.getDirection()).isEqualTo(FertilizerRulesService.ORGANIC);
        advance(DAY / 10);
        facts(null, field(12, null, "LIQUID_MANURE", 2), field(13, null, "NONE", 0)); // still running: no second one
        assertThat(inspections()).hasSize(1);

        sg.setCurrentGameTime(first.getDeadlineGameTime());
        day();
        assertThat(first.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(first.getResolution()).isEqualTo("WARNING");
        assertThat(sg.getFertilizerViolations()).isEqualTo(1);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).noneMatch(o -> o.getType() == InstructionType.MONEY_TRANSACTION);

        advance(DAY / 10);
        facts(null, field(12, null, "LIQUID_MANURE", 2), field(13, null, "MANURE", 1));
        ServiceCase second = inspections().getFirst();
        assertThat(second.getReference()).isEqualTo("13");
        sg.setCurrentGameTime(second.getDeadlineGameTime());
        day();
        assertThat(second.getResolution()).isEqualTo("FINED");
        assertThat(second.getCostAmount()).isEqualTo(1000);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anyMatch(o -> o.getType() == InstructionType.MONEY_TRANSACTION);
        assertThat(publicActions.findAll()).anyMatch(a -> a.getType() == PublicActionType.AUTHORITY_FINE);
        assertThat(narrations()).contains("AUTHORITY_INSPECTION_NOTICE", "AUTHORITY_INSPECTION_RESULT");
    }

    @Test
    void outsideTheClosedPeriodOnGrasslandAndWithMineralFertiliserNothingHappens() {
        sg.setCalPeriod(8); // October
        facts(null, field(12, null, "NONE", 0));
        advance(DAY / 10);
        facts(null, field(12, null, "LIQUID_MANURE", 1));
        assertThat(inspections()).isEmpty();

        sg.setCalPeriod(9);
        facts(null, field(12, "GRASS", "LIQUID_MANURE", 1));
        advance(DAY / 10);
        facts(null, field(12, "GRASS", "LIQUID_MANURE", 2)); // grassland
        facts(null, field(12, null, "FERTILIZER", 3)); // mineral fertiliser
        assertThat(inspections()).isEmpty();
    }

    @Test
    void fallbackCountsEveryRiseAndTheSwitchTurnsItOff() {
        assertThat(FertilizerRulesService.finding(null, 2, 1, props.getFormulas().getFertilizerRules()))
                .contains(FertilizerRulesService.UNKNOWN); // older mod without sprayType
        props.getFormulas().getFertilizerRules().setRequireSprayType(false);
        assertThat(FertilizerRulesService.finding("FERTILIZER", 2, 1, props.getFormulas().getFertilizerRules()))
                .contains(FertilizerRulesService.UNKNOWN);
        assertThat(FertilizerRulesService.finding("FERTILIZER", 1, 1, props.getFormulas().getFertilizerRules()))
                .isEqualTo(Optional.empty());

        sg.setBurdenFertilizer(false);
        facts(null, field(12, null, "NONE", 0));
        advance(DAY / 10);
        facts(null, field(12, null, "MANURE", 1));
        assertThat(inspections()).isEmpty();
    }

    @Test
    void fullSlurryStoreWarnsAfterSomeDaysAndOctoberReminds() {
        String full = "\"husbandries\": [{ \"husbandryUniqueId\": \"hus_00003\", \"health\": 80, \"food\": 0.5, "
                + "\"conditions\": [{ \"title\": \"Gülle\", \"ratio\": 0.9 }] }]";
        facts(full, field(12, null, "NONE", 0));
        day();
        advance(4 * DAY);
        day();
        assertThat(narrations()).doesNotContain("SLURRY_WARNING");
        advance(DAY);
        day();
        assertThat(narrations()).containsOnlyOnce("SLURRY_WARNING");
        advance(DAY);
        day(); // cooldown
        assertThat(narrations()).containsOnlyOnce("SLURRY_WARNING");

        sg.setCurrentGameTime(sg.getCalMonthStartGameTime() + 11 * 30 * DAY); // October of the next year
        facts(full, field(12, null, "NONE", 0));
        rules.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(narrations()).contains("SLURRY_REMINDER");
    }
}
