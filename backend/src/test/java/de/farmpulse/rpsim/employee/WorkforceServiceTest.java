package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V2 R2-A0 / A4 / A5: employee list for the mod, workload from real hours, effect scaling, strike. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class WorkforceServiceTest {

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired SatisfactionService satisfaction;
    @Autowired WorkforceService workforce;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, 1_000_000);
    }

    private Employee employee(String name, JobRole role, int skill) {
        var c = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, name);
        return hiring.createEmployee(sg, c, role, skill, 2400);
    }

    private List<JsonNode> rosters() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.EMPLOYEE_ROSTER)
                .map(OutboxInstruction::getPayloadJson).map(json::readTree).toList();
    }

    private void facts(long gameTime, String workedGameMs) {
        String doc = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), gameTime, 1_000_000),
                "\"workforce\": { \"activeJobs\": [], \"workedGameMs\": " + workedGameMs + " }");
        var snap = fx.snapshot(sg, gameTime, 1_000_000, doc);
        workforce.onFacts(new BridgeEvents.FactsIngested(sg.getId(), snap.getId(), gameTime, false));
    }

    @Test
    void theListIsSentOnChangesSortedBySkill() {
        Employee low = employee("Paul Weber", JobRole.MACHINE_OPERATOR, 40);
        Employee high = employee("Klaus Berger", JobRole.MACHINE_OPERATOR, 90);
        employee("Mia Roth", JobRole.MECHANIC, 60);
        JsonNode last = rosters().getLast();
        assertThat(last.path("employees").findValuesAsString("name")).containsExactly("Klaus Berger", "Mia Roth", "Paul Weber");
        assertThat(last.path("employees").get(0).path("employeeId").asLong()).isEqualTo(high.getId());
        assertThat(last.path("helperWageMode").asString()).isEqualTo("EMPLOYEES");
        int sent = rosters().size();
        assertThat(workforce.sync(sg)).as("unchanged list is not sent again").isFalse();
        satisfaction.timeOff(low, 2);
        assertThat(rosters()).hasSize(sent + 1);
        assertThat(rosters().getLast().path("employees").get(2).path("status").asString()).isEqualTo("ON_LEAVE");
        sg.setStrictHelperLimit(true);
        assertThat(workforce.sync(sg)).isTrue();
        assertThat(rosters().getLast().path("strictHelperLimit").asBoolean()).isTrue();
        hiring.dismiss(sg, high.getId());
        assertThat(rosters().getLast().path("employees")).hasSize(2);
    }

    @Test
    void theListIsSentAgainAfterARewind() {
        employee("Klaus Berger", JobRole.MACHINE_OPERATOR, 90);
        int sent = rosters().size();
        facts(sg.getCurrentGameTime() + 1000, "{}");
        assertThat(rosters()).hasSize(sent);
        facts(GameTime.days(2), "{}"); // reload without saving: game time went back
        assertThat(rosters()).hasSize(sent + 1);
    }

    @Test
    void workloadOfMachineOperatorsFollowsTheRealHours() {
        Employee op = employee("Klaus Berger", JobRole.MACHINE_OPERATOR, 70);
        Employee mechanic = employee("Mia Roth", JobRole.MECHANIC, 70);
        String id = String.valueOf(op.getId());
        facts(sg.getCurrentGameTime(), "{ \"" + id + "\": 0 }");
        assertThat(sg.isWorkforceTracked()).isTrue();
        facts(sg.getCurrentGameTime() + 1, "{ \"" + id + "\": " + GameTime.hours(11) + " }");
        double before = op.getWorkload();
        workforce.onDay(new GameDayPassedEvent(sg.getId(), 10, sg.getCurrentGameTime()));
        // 11 h worked, 8 h target: 3 overtime hours x 2 points
        assertThat(op.getWorkload()).isCloseTo(before - 6, within(1e-6));
        assertThat(op.getWorkedMsToday()).isZero();
        assertThat(op.getWorkedMsMonth()).isEqualTo(GameTime.hours(11));
        // a day with 4 hours: 4 hours below target x 0.5 points recovery
        facts(sg.getCurrentGameTime() + 2, "{ \"" + id + "\": " + GameTime.hours(15) + " }");
        workforce.onDay(new GameDayPassedEvent(sg.getId(), 11, sg.getCurrentGameTime()));
        assertThat(op.getWorkload()).isCloseTo(before - 4, within(1e-6));
        // no simulated decay for the operator, the mechanic keeps it
        assertThat(satisfaction.workloadDecayPerDay(op)).isZero();
        assertThat(satisfaction.workloadDecayPerDay(mechanic)).isPositive();
        assertThat(WorkforceService.hoursThisMonth(op)).isCloseTo(15, within(1e-6));
        assertThat(WorkforceService.hoursThisMonth(mechanic)).isNull();
    }

    @Test
    void aReloadDoesNotCountTimeTwice() {
        Employee op = employee("Klaus Berger", JobRole.MACHINE_OPERATOR, 70);
        String id = String.valueOf(op.getId());
        facts(sg.getCurrentGameTime(), "{ \"" + id + "\": " + GameTime.hours(5) + " }");
        facts(sg.getCurrentGameTime() + 1, "{ \"" + id + "\": " + GameTime.hours(3) + " }");
        facts(sg.getCurrentGameTime() + 2, "{ \"" + id + "\": " + GameTime.hours(4) + " }");
        assertThat(op.getWorkedMsMonth()).isEqualTo(GameTime.hours(6));
    }

    /** A positive effect needs category-max > 100 (QUESTIONS AP-4.5), so the scale itself is checked here. */
    @Test
    void thePositiveEffectOfOperatorsScalesWithTheHours() {
        Employee op = employee("Klaus Berger", JobRole.MACHINE_OPERATOR, 70);
        assertThat(satisfaction.effectScale(op)).as("without worked time from the mod").isEqualTo(1);
        sg.setWorkforceTracked(true);
        // fallback calendar: 1 day per month -> target 8 h; 2 h driven = 25 %
        op.setWorkedMsMonth(GameTime.hours(2));
        assertThat(satisfaction.effectScale(op)).isCloseTo(0.25, within(1e-9));
        op.setWorkedMsMonth(GameTime.hours(20));
        assertThat(satisfaction.effectScale(op)).isEqualTo(1);
        satisfaction.bookMonthlyEffect(op);
        assertThat(op.getWorkedMsLastMonth()).isEqualTo(GameTime.hours(20));
        assertThat(op.getWorkedMsMonth()).isZero();
        op.setStrikeSinceGameTime(sg.getCurrentGameTime());
        assertThat(satisfaction.effectScale(op)).as("no bonus during a strike").isZero();
    }

    @Test
    void longDissatisfactionLeadsToAStrikeThatEndsWhenThingsImprove() {
        Employee op = employee("Klaus Berger", JobRole.MACHINE_OPERATOR, 70);
        op.setPayFairness(10);
        op.setWorkload(10);
        op.setAppreciation(10);
        op.setLowSatisfactionSinceGameTime(sg.getCurrentGameTime() - GameTime.days(22));
        satisfaction.checkEscalation(op);
        assertThat(op.getStrikeSinceGameTime()).isNotNull();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(NarrationJob::getEventType).contains("EMPLOYEE_STRIKE");
        assertThat(rosters().getLast().path("employees").get(0).path("status").asString()).isEqualTo("STRIKE");
        // a big raise lifts the score above the strike threshold
        op.setPayFairness(100);
        op.setWorkload(100);
        op.setAppreciation(100);
        satisfaction.checkEscalation(op);
        assertThat(op.getStrikeSinceGameTime()).isNull();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(NarrationJob::getEventType)
                .contains("EMPLOYEE_STRIKE_ENDED");
        assertThat(rosters().getLast().path("employees").get(0).path("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void workloadDeltasAtTheBoundaries() {
        assertThat(workforce.operatorWorkloadDelta(8)).isZero();
        assertThat(workforce.operatorWorkloadDelta(0)).isEqualTo(4);
        assertThat(workforce.operatorWorkloadDelta(24)).isEqualTo(-32);
        assertThat(workforce.keeperWorkloadDelta(80)).isEqualTo(0.5);
        assertThat(workforce.keeperWorkloadDelta(160)).isEqualTo(-3);
        assertThat(workforce.keeperWorkloadDelta(0)).isEqualTo(0.5);
    }
}
