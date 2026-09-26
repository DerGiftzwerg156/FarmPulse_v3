package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.ContractRepository;
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

/** TODO T-22: maintenance contract of the workshop. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class MaintenanceServiceTest {

    static final String VEHICLES = "\"vehicles\": [{ \"uniqueId\": \"veh_a\", \"value\": 100000, \"condition\": 30 },"
            + "{ \"uniqueId\": \"veh_b\", \"value\": 50000, \"condition\": 65 },"
            + "{ \"uniqueId\": \"veh_c\", \"value\": 50000, \"condition\": 50 },"
            + "{ \"uniqueId\": \"veh_d\", \"value\": 50000, \"condition\": 69 },"
            + "{ \"uniqueId\": \"veh_e\", \"value\": 50000, \"condition\": 95 }]";

    @Autowired Fixtures fx;
    @Autowired MaintenanceService maintenance;
    @Autowired ContractRepository contracts;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(),
                100_000).replace("\"vehicles\": [{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }]", VEHICLES));
    }

    private List<OutboxInstruction> repairs() {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == InstructionType.REPAIR_VEHICLE).toList();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private Contract active() {
        Contract c = maintenance.offer(sg);
        maintenance.accept(sg, c.getId());
        sg.setCurrentGameTime(sg.getCurrentGameTime() + 1);
        return c;
    }

    @Test
    void feeFollowsTheVehicleValue() {
        assertThat(maintenance.monthlyFee(sg)).isEqualTo(600); // 300 000 × 0.2 %
        Contract c = maintenance.offer(sg);
        assertThat(c.getMonthlyAmount()).isEqualTo(600);
        assertThat(narrations()).contains("CHARACTER_INTRODUCTION", "MAINTENANCE_OFFER");
    }

    @Test
    void monthlyServiceRepairsTheMostWornVehicles() {
        Contract c = active();
        maintenance.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        assertThat(repairs()).extracting(OutboxInstruction::getPayloadJson)
                .containsExactly("{\"vehicleId\":\"veh_a\"}", "{\"vehicleId\":\"veh_c\"}", "{\"vehicleId\":\"veh_b\"}");
        assertThat(repairs()).allSatisfy(o -> assertThat(o.getRelatedEntityType()).isEqualTo(MaintenanceService.RELATED));
        List<ServiceCase> done = cases.findBySavegameOrderByIdDesc(sg);
        assertThat(done).hasSize(3).allSatisfy(sc -> {
            assertThat(sc.getKind()).isEqualTo(CaseKind.REPAIR);
            assertThat(sc.getResolution()).isEqualTo("INCLUDED");
            assertThat(sc.getContractId()).isEqualTo(c.getId());
        });
        assertThat(narrations()).contains("MAINTENANCE_REPAIRED");
        assertThatThrownBy(() -> maintenance.offer(sg)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void noRepairsWhileTheFeeIsOverdue() {
        Contract c = active();
        c.setPaymentOverdue(true);
        maintenance.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        assertThat(repairs()).isEmpty();
    }

    @Test
    void withoutContractAHintAndOneFirstOffer() {
        maintenance.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        assertThat(narrations()).contains("REPAIR_HINT", "MAINTENANCE_OFFER");
        assertThat(cases.findBySavegameOrderByIdDesc(sg)).singleElement().satisfies(sc -> {
            assertThat(sc.getResolution()).isEqualTo("HINT");
            assertThat(sc.getReference()).isEqualTo("veh_a");
        });
        assertThat(repairs()).isEmpty();
        maintenance.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        assertThat(narrations().stream().filter("REPAIR_HINT"::equals)).hasSize(1);
        assertThat(contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.MAINTENANCE,
                List.of(ContractStatus.OFFERED))).hasSize(1);
    }

    @Test
    void failedRepairAndMissedFees() {
        Contract c = active();
        List<ServiceCase> done = maintenance.service(sg, c);
        assertThat(maintenance.onRepairFailed(done.get(0).getId(), "VEHICLE_NOT_FOUND")).isTrue();
        assertThat(done.get(0).getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(done.get(0).getResolution()).isEqualTo("NOT_REPAIRED");
        maintenance.onPaymentMissed(new ContractEvents.PaymentMissed(sg.getId(), c.getId(), 1));
        assertThat(narrations()).contains("MAINTENANCE_FEE_OVERDUE");
        maintenance.onPaymentMissed(new ContractEvents.PaymentMissed(sg.getId(), c.getId(), 2));
        assertThat(c.getStatus()).isEqualTo(ContractStatus.ENDED);
        assertThat(c.getEndReason()).isEqualTo("FEE_MISSED");
    }

    @Test
    void cancelEndsTheContract() {
        Contract c = active();
        maintenance.cancel(sg, c.getId());
        assertThat(c.getStatus()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(narrations()).contains("MAINTENANCE_CANCELLED");
    }
}
