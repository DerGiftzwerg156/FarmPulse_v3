package de.farmpulse.rpsim.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos.Vehicle;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.Employee;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.JobRole;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V2 R2-A6: the employed mechanic repairs partially after the maintenance contract. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class MechanicServiceTest {

    @Autowired Fixtures fx;
    @Autowired HiringService hiring;
    @Autowired MechanicService mechanics;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired ServiceCaseRepository cases;
    @Autowired NarrationJobRepository jobs;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        String vehicles = "\"vehicles\": [{ \"uniqueId\": \"veh_a\", \"value\": 1, \"condition\": 40 },"
                + " { \"uniqueId\": \"veh_b\", \"value\": 1, \"condition\": 60 }, { \"uniqueId\": \"veh_c\", \"value\": 1, \"condition\": 85 },"
                + " { \"uniqueId\": \"veh_d\", \"value\": 1, \"condition\": 95 }]";
        String facts = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000)
                .replace("\"vehicles\": [{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }]", vehicles);
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, facts);
    }

    private static Vehicle v(String id, double condition) {
        return new Vehicle(id, 1.0, condition);
    }

    @Test
    void thePlanSpendsTheCapacityOnTheMostWornVehiclesBelowTheThreshold() {
        List<Vehicle> vehicles = List.of(v("a", 40), v("b", 60), v("c", 85), v("d", 95));
        var plan = MechanicService.plan(vehicles, Set.of(), 70, 90);
        assertThat(plan).extracting(MechanicService.Repair::vehicleId).containsExactly("a", "b");
        assertThat(plan.get(0).conditionAfter()).isEqualTo(100);
        assertThat(plan.get(1).conditionAfter()).isEqualTo(70);
        assertThat(plan.get(1).targetDamage()).isCloseTo(0.3, within(1e-9));
        assertThat(MechanicService.plan(vehicles, Set.of("a"), 0, 90)).isEmpty();
        assertThat(MechanicService.plan(vehicles, Set.of("a"), 1000, 90)).extracting(MechanicService.Repair::vehicleId)
                .containsExactly("b", "c");
    }

    @Test
    void theMechanicRepairsAfterTheContractAndReports() {
        Employee m = hiring.createEmployee(sg, fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Mia Roth"),
                JobRole.MECHANIC, 100, 2700);
        // the maintenance contract already repaired veh_a this month
        ServiceCase contract = new ServiceCase();
        contract.setSavegame(sg);
        contract.setKind(CaseKind.REPAIR);
        contract.setCharacter(m.getCharacter());
        contract.setReference("veh_a");
        contract.setStatus(CaseStatus.SETTLED);
        contract.setResolution("INCLUDED");
        contract.setGameTime(sg.getCurrentGameTime());
        contract.setCreatedAt(Instant.now());
        cases.save(contract);
        mechanics.onMonth(new GameMonthPassedEvent(sg.getId(), 10, sg.getCurrentGameTime()));
        var repairs = outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getType() == InstructionType.REPAIR_VEHICLE).map(o -> json.readTree(o.getPayloadJson())).toList();
        // capacity 60 x 1.0 x effect multiplier (< 1): veh_b first, then veh_c; veh_a stays with the contract
        assertThat(repairs).extracting(r -> r.path("vehicleId").asString()).startsWith("veh_b").doesNotContain("veh_a", "veh_d");
        assertThat(repairs.getFirst().path("targetDamage").asDouble()).isBetween(0.0, 0.4);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(NarrationJob::getEventType).contains("MECHANIC_REPORT");
        // a second run in the same month repairs nothing twice
        int before = repairs.size();
        mechanics.onMonth(new GameMonthPassedEvent(sg.getId(), 10, sg.getCurrentGameTime()));
        Set<String> all = new HashSet<>(outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> o.getType() == InstructionType.REPAIR_VEHICLE).map(o -> json.readTree(o.getPayloadJson())
                        .path("vehicleId").asString()).toList());
        assertThat(all).hasSize(before);
    }

    @Test
    void aMechanicOnStrikeRepairsNothing() {
        Employee m = hiring.createEmployee(sg, fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Mia Roth"),
                JobRole.MECHANIC, 100, 2700);
        m.setStrikeSinceGameTime(sg.getCurrentGameTime());
        mechanics.onMonth(new GameMonthPassedEvent(sg.getId(), 10, sg.getCurrentGameTime()));
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).noneMatch(o -> o.getType() == InstructionType.REPAIR_VEHICLE);
    }
}
