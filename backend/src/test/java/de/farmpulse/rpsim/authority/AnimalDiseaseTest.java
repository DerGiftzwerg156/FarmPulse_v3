package de.farmpulse.rpsim.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.EnumSet;
import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.AnimalDisease;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
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
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-B4: animal disease and restricted zone (owner decisions 2026-10-05). One game day per period. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class AnimalDiseaseTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired AnimalDiseaseService diseases;
    @Autowired DiseaseZones zones;
    @Autowired NarrationJobRepository jobs;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired ServiceCaseRepository cases;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    long t0;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        t0 = sg.getCurrentGameTime();
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(t0);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Frau Kessler");
        fx.character(sg, CharacterRole.COOPERATIVE, CharacterCategory.MANDATORY, "Genossenschaft Erlengrund");
        props.getFormulas().getAnimalDisease().setProbabilityPerMonth(1.0);
        facts(80);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setAnimalDisease(new RpsimProperties.AnimalDisease());
    }

    /** The default facts (24 cows in hus_00003) with the stable's health. */
    private void facts(double health) {
        long t = sg.getCurrentGameTime();
        fx.snapshot(sg, t, 100_000, TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), t, 100_000),
                "\"husbandries\": [{ \"husbandryUniqueId\": \"hus_00003\", \"health\": " + health
                        + ", \"food\": 0.5, \"conditions\": [] }]"));
    }

    private void month() {
        diseases.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    @Test
    void anOutbreakBlocksTheTradeChecksTheStableAndLiftsAfterTheZoneMonths() {
        month();
        AnimalDisease d = diseases.active(sg).orElseThrow();
        assertThat(d.getDiseaseKey()).isEqualTo("BLUETONGUE"); // the only disease hitting cows
        assertThat(d.types()).containsExactly("SHEEP", "COW");
        assertThat(d.getEndsMonthIndex()).isEqualTo(3);
        assertThat(narrations()).contains("ANIMAL_DISEASE_DECLARED", "ANIMAL_DISEASE_NEWS", "ANIMAL_DISEASE_VET_CHECK",
                "AUTHORITY_INSPECTION_NOTICE");
        var vet = outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .findFirst().orElseThrow();
        assertThat(json.readTree(vet.getPayloadJson()).path("amount").asLong()).isEqualTo(-(80 + 4 * 24) * 2);
        assertThat(json.readTree(vet.getPayloadJson()).path("reason").asString()).isEqualTo("VET_INVOICE");

        assertThat(zones.blocked(sg, "COW")).isTrue();
        assertThat(zones.blocked(sg, "PIG")).isFalse();
        assertThatThrownBy(() -> zones.requireOpen(sg, "SHEEP")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Sperrzone");

        ServiceCase req = cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.AUTHORITY_INSPECTION)).getFirst();
        assertThat(req.getTitle()).isEqualTo(AnimalDiseaseService.RULE);
        assertThat(req.getDeadlineGameTime()).isEqualTo(t0 + 10 * DAY);
        sg.setCurrentGameTime(req.getDeadlineGameTime());
        facts(50); // below the required 60 %
        diseases.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(req.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(req.getResolution()).isEqualTo("FINED");
        assertThat(req.getCostAmount()).isEqualTo(1000);

        sg.setCurrentGameTime(t0 + 2 * DAY); // still inside the zone, no second outbreak
        month();
        assertThat(diseases.list(sg)).hasSize(1);
        sg.setCurrentGameTime(t0 + 3 * DAY);
        month();
        assertThat(d.getStatus()).isEqualTo(AnimalDisease.LIFTED);
        assertThat(narrations()).contains("ANIMAL_DISEASE_LIFTED");
        assertThat(zones.blocked(sg, "COW")).isFalse();
        assertThat(zones.priceFactor(sg, "COW")).isCloseTo(0.8, within(1e-9));
        sg.setCurrentGameTime(t0 + 3 * DAY + DAY * 3 / 2);
        assertThat(zones.priceFactor(sg, "COW")).isCloseTo(0.9, within(1e-9));
        assertThat(zones.priceFactor(sg, "PIG")).isEqualTo(1.0);
        sg.setCurrentGameTime(t0 + 6 * DAY);
        assertThat(zones.priceFactor(sg, "COW")).isEqualTo(1.0);

        month(); // cooldown of 12 months after the lifting
        assertThat(diseases.list(sg)).hasSize(1);
        sg.setCurrentGameTime(t0 + 15 * DAY);
        month();
        assertThat(diseases.list(sg)).hasSize(2);
    }

    @Test
    void noOutbreakInTheIdyllicWorldModeWhenSwitchedOffOrWithoutAffectedAnimals() {
        sg.setTonePreset(TonePreset.IDYLLIC);
        month();
        sg.setTonePreset(TonePreset.REALISTIC);
        sg.setBurdenDisease(false);
        month();
        sg.setBurdenDisease(true);
        props.getFormulas().getAnimalDisease().setDiseases(List.of(new RpsimProperties.Disease("ASP", List.of("PIG"))));
        month(); // no pigs on the farm
        assertThat(diseases.list(sg)).isEmpty();
    }
}
