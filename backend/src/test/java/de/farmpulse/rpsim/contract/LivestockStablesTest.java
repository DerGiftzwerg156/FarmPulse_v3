package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.HiringService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V2 R2-A7: vet emergency, animal keeper warning and breeding advice from the real stable values. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class LivestockStablesTest {

    @Autowired Fixtures fx;
    @Autowired LivestockService livestock;
    @Autowired HiringService hiring;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
    }

    private FarmFacts stable(double health, double food, double water, Double productivity) {
        String doc = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000),
                "\"husbandries\": [{ \"husbandryUniqueId\": \"hus_00003\", \"health\": " + health + ", \"food\": " + food
                        + (productivity == null ? "" : ", \"productivity\": " + productivity)
                        + ", \"conditions\": [{ \"title\": \"Wasser\", \"ratio\": " + water + " }] }]");
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, doc);
        return json.readValue(doc, FarmFacts.class);
    }

    private long count(String type) {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).filter(type::equals).count();
    }

    @Test
    void aSickStableBringsTheVetForAnEmergencyOncePerCooldown() {
        stable(35, 0.8, 0.9, 0.5);
        livestock.onDayStables(new GameDayPassedEvent(sg.getId(), 10, sg.getCurrentGameTime()));
        assertThat(count("VET_EMERGENCY")).isEqualTo(1);
        var invoice = outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .map(o -> json.readTree(o.getPayloadJson())).findFirst().orElseThrow();
        // (80 + 4 x 24 cows) x 2.5
        assertThat(invoice.path("amount").asLong()).isEqualTo(-440);
        assertThat(invoice.path("reason").asString()).isEqualTo("VET_INVOICE");
        livestock.onDayStables(new GameDayPassedEvent(sg.getId(), 11, sg.getCurrentGameTime()));
        assertThat(count("VET_EMERGENCY")).as("cooldown").isEqualTo(1);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(6));
        stable(35, 0.8, 0.9, 0.5);
        livestock.onDayStables(new GameDayPassedEvent(sg.getId(), 16, sg.getCurrentGameTime()));
        assertThat(count("VET_EMERGENCY")).isEqualTo(2);
    }

    @Test
    void theAnimalKeeperWarnsAboutLowWaterOrFood() {
        FarmFacts ok = stable(90, 0.8, 0.9, 0.9);
        Employee keeper = hiring.createEmployee(sg, fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Jonas Hahn"),
                JobRole.ANIMAL_KEEPER, 70, 2200);
        assertThat(livestock.keeperWarning(sg, ok)).isFalse();
        FarmFacts dry = stable(90, 0.8, 0.1, 0.9);
        assertThat(livestock.keeperWarning(sg, dry)).isTrue();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("\"lowestWaterPercent\":10");
        assertThat(livestock.keeperWarning(sg, dry)).as("cooldown").isFalse();
        keeper.setLastStableWarningGameTime(null);
        keeper.setStrikeSinceGameTime(sg.getCurrentGameTime());
        assertThat(livestock.keeperWarning(sg, dry)).as("a striking keeper does not warn").isFalse();
    }

    @Test
    void withoutKeeperNobodyWarns() {
        assertThat(livestock.keeperWarning(sg, stable(90, 0.05, 0.05, null))).isFalse();
    }

    @Test
    void theBreedingAdviceNamesTheRealProductivity() {
        stable(90, 0.8, 0.9, 0.42);
        livestock.breedingAdvice(sg, new LivestockService.Herd("COW", 24, 96_000));
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("\"productivityPercent\":42");
    }
}
