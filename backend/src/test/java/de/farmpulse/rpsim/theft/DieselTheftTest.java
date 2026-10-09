package de.farmpulse.rpsim.theft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.DieselTheft;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TonePreset;
import de.farmpulse.rpsim.domain.VillageNews;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.VillageNewsRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-D8: diesel theft, insurance module and tank lock (owner decisions 2026-10-05). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class DieselTheftTest {

    static final String VEHICLES = """
            "vehicles": [{ "uniqueId": "veh_00042", "value": 285000, "condition": 82, "name": "Fendt 724",
                           "fuel": { "liters": 400, "capacity": 500 } },
                         { "uniqueId": "veh_00043", "value": 85000, "condition": 90, "name": "Deutz 5105",
                           "fuel": { "liters": 60, "capacity": 120 } }]""";

    @Autowired Fixtures fx;
    @Autowired DieselTheftService thefts;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired VillageNewsRepository news;
    @Autowired ContractRepository contracts;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setCurrentGameTime(GameTime.days(10)); // midnight
        facts("");
    }

    @AfterEach
    void restore() {
        props.getFormulas().setDieselTheft(new RpsimProperties.DieselTheft());
    }

    private void facts(String positions) {
        String doc = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000)
                .replaceFirst("\"vehicles\": \\[\\{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }]",
                        VEHICLES.replace("\n", " "));
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, TestData.withFields(doc, "\"vehiclePositions\": [" + positions + "]"));
    }

    private List<OutboxInstruction> fuel() {
        return outbox.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.VEHICLE_FUEL);
    }

    private JsonNode lastMoney() {
        return json.readTree(outbox.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.MONEY_TRANSACTION).getLast()
                .getPayloadJson());
    }

    private void ack(DieselTheft t, long liters) {
        thefts.onAck(new BridgeEvents.InstructionAcked(sg.getId(), t.getInstructionId(), "APPLIED", DieselTheftService.RELATED,
                t.getId(), Map.of("liters", liters)));
    }

    @Test
    void aParkedVehicleWithEnoughDieselIsDrainedInTheNextNight() {
        DieselTheft t = thefts.plan(sg).orElseThrow();
        assertThat(t.getVehicleId()).isEqualTo("veh_00042"); // the Deutz has too little diesel
        assertThat(t.getLiters()).isBetween(120L, 240L); // 30-60 % of 400 l, at most 300
        OutboxInstruction ins = fuel().getFirst();
        JsonNode p = json.readTree(ins.getPayloadJson());
        assertThat(p.path("vehicleId").asString()).isEqualTo("veh_00042");
        assertThat(p.path("delta").asLong()).isEqualTo(-t.getLiters());
        assertThat(ins.getGameTimeEarliest()).isEqualTo(GameTime.days(10) + GameTime.hours(22));

        ack(t, 150);
        assertThat(t.getStatus()).isEqualTo(DieselTheft.DONE);
        assertThat(t.getDamage()).isEqualTo(240);
        assertThat(t.getInsurancePayout()).isNull();
        var report = jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, "DIESEL_THEFT_REPORT");
        assertThat(report).hasSize(1);
        assertThat(report.getFirst().getCharacter().getRole()).isEqualTo(CharacterRole.POLICE);
        assertThat(news.findBySavegameOrderByIdAsc(sg)).extracting(VillageNews::getKind).contains("DIESEL_THEFT");
    }

    @Test
    void theInsuranceModulePaysAboveTheMinimumDamage() {
        Character agent = fx.character(sg, CharacterRole.INSURANCE_AGENT, CharacterCategory.MANDATORY, "Herr Voss");
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.INSURANCE);
        c.setStatus(ContractStatus.ACTIVE);
        c.setCharacter(agent);
        c.setMonthlyAmount(60);
        c.setCreatedAt(Instant.now());
        contracts.save(c);
        thefts.theftCover(sg, c.getId(), true);
        assertThat(c.getMonthlyAmount()).isEqualTo(68);
        assertThat(c.isTheftCover()).isTrue();

        DieselTheft small = thefts.plan(sg).orElseThrow();
        ack(small, 90); // 144 € - below 150 €
        assertThat(small.getInsurancePayout()).isNull();
        DieselTheft big = thefts.plan(sg).orElseThrow();
        ack(big, 100); // 160 €
        assertThat(big.getInsurancePayout()).isEqualTo(160);
        assertThat(lastMoney().path("reason").asString()).isEqualTo("INSURANCE_PAYOUT");

        thefts.theftCover(sg, c.getId(), false);
        assertThat(c.getMonthlyAmount()).isEqualTo(60);
    }

    @Test
    void aRefusedTheftIsTriedAgainAtMostThreeTimes() {
        DieselTheft t = thefts.plan(sg).orElseThrow();
        assertThat(thefts.onInstructionFailed(t.getId(), "VEHICLE_IN_USE")).isTrue();
        assertThat(t.getAttempts()).isEqualTo(2);
        assertThat(fuel()).hasSize(2);
        assertThat(t.getInstructionId()).isEqualTo(fuel().getLast().getInstructionId());
        thefts.onInstructionFailed(t.getId(), "VEHICLE_IN_USE");
        assertThat(t.getAttempts()).isEqualTo(3);
        thefts.onInstructionFailed(t.getId(), "VEHICLE_IN_USE");
        assertThat(t.getStatus()).isEqualTo(DieselTheft.FAILED);
        assertThat(fuel()).hasSize(3);
    }

    @Test
    void drivenVehiclesTankLocksAndTheSwitches() {
        facts("{ \"uniqueId\": \"veh_00042\", \"x\": 1, \"z\": 2 }");
        assertThat(thefts.plan(sg)).isEmpty(); // being driven

        facts("");
        thefts.buyTankLock(sg, "veh_00042");
        assertThat(lastMoney().path("amount").asLong()).isEqualTo(-250);
        assertThat(lastMoney().path("reason").asString()).isEqualTo("TANK_LOCK");
        assertThatThrownBy(() -> thefts.buyTankLock(sg, "veh_00042")).isInstanceOf(BusinessRuleException.class);
        assertThat(thefts.targets(sg, null)).isEmpty();

        props.getFormulas().getDieselTheft().setProbabilityPerMonth(1.0);
        sg.setTonePreset(TonePreset.IDYLLIC);
        thefts.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(fuel()).isEmpty(); // off in the idyllic world
        sg.setTonePreset(TonePreset.REALISTIC);
        sg.setBurdenDieselTheft(false);
        thefts.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(fuel()).isEmpty();
        sg.setBurdenDieselTheft(true);
        thefts.onMonth(new GameMonthPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(fuel()).hasSize(1); // the locked vehicle is still possible, only less likely
    }
}
