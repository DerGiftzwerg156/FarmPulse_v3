package de.farmpulse.rpsim.dealer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.api.DealerOrderController;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MachineLoan;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.farmwork.LoanedVehicles;
import de.farmpulse.rpsim.farmwork.MachineLoanService;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.MachineLoanRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
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

/**
 * Roadmap vehicle dealer RFH-V1 (owner decisions 2026-10-15): the dealer's demo machine - VEHICLE_SPAWN with price 0,
 * 2 days of a 30-day month, pick-up with VEHICLE_REMOVE, one demo at a time independent of R31-A2, each machine once per
 * game year, 500 € per late day, a disappeared machine costs its value and trust like R31-A2.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class DealerDemoTest {

    static final long DAY = GameTime.MS_PER_DAY;

    @Autowired Fixtures fx;
    @Autowired DealerOrderController controller;
    @Autowired DealerDemoService service;
    @Autowired MachineLoanService machineLoans;
    @Autowired LoanedVehicles loaned;
    @Autowired MachineLoanRepository loans;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired FailedInstructionService failed;
    @Autowired SavegameRepository savegames;
    @Autowired SavegameContext context;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired JsonMapper json;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setStoreCatalogJson(MachineCatalogTest.CATALOG);
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(30);
        sg.setCalPeriod(1);
        sg.setCurrentGameTime(2 * DAY + 10 * GameTime.MS_PER_HOUR);
        savegames.save(sg);
        context.setCurrentBridgeSavegameId(sg.getBridgeSavegameId());
        export(null);
    }

    /** An export of game year 1 with the demo machine (uniqueId) on the farm, or without it (null). */
    private void export(String demoId) {
        String vehicles = demoId == null ? "[]" : "[{ \"uniqueId\": \"" + demoId + "\", \"value\": 240000, \"condition\": 100 }]";
        String f = TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100000)
                .replace("[{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }]", vehicles),
                "\"calendar\": { \"period\": 1, \"dayInPeriod\": 3, \"daysPerPeriod\": 30, \"year\": 1, \"monotonicDay\": 2 }");
        fx.snapshot(sg, sg.getCurrentGameTime(), 100000, f);
    }

    private List<OutboxInstruction> of(InstructionType type) {
        return outbox.findBySavegameAndTypeOrderByIdAsc(sg, type);
    }

    private void ack(OutboxInstruction ins, String status, String message, Map<String, Object> result) {
        ins.setStatus("APPLIED".equals(status) ? InstructionStatus.APPLIED : InstructionStatus.FAILED);
        ins.setAckMessage(message);
        var e = new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), status, ins.getRelatedEntityType(),
                ins.getRelatedEntityId(), result);
        service.onAck(e);
        failed.onAck(e);
    }

    private List<String> mails(String type) {
        return jobs.findBySavegameAndEventTypeOrderByIdAsc(sg, type).stream().map(j -> j.getEventType()).toList();
    }

    /** Requested and delivered: the machine stands on the shop place. */
    private MachineLoan delivered() {
        controller.requestDemo(new DealerOrderController.DemoRequest(MachineCatalogTest.FENDT));
        return arrived();
    }

    private MachineLoan arrived() {
        OutboxInstruction spawn = of(InstructionType.VEHICLE_SPAWN).getLast();
        ack(spawn, "APPLIED", null, Map.of("vehicleId", "veh_demo"));
        export("veh_demo");
        service.tick(sg);
        return loans.findById(spawn.getRelatedEntityId()).orElseThrow();
    }

    @Test
    void theDemoComesFreeForTwoDaysAndIsNoAssetOfTheFarm() {
        var state = controller.requestDemo(new DealerOrderController.DemoRequest(MachineCatalogTest.FENDT));
        assertThat(state.running().status()).isEqualTo(MachineLoan.DELIVERING);
        OutboxInstruction spawn = of(InstructionType.VEHICLE_SPAWN).getLast();
        var p = json.readTree(spawn.getPayloadJson());
        assertThat(p.path("storeXmlFilename").asString()).isEqualTo(MachineCatalogTest.FENDT);
        assertThat(p.path("price").asLong()).isZero();
        assertThat(p.has("place")).isFalse(); // the shop place

        MachineLoan l = arrived();
        assertThat(l.getStatus()).isEqualTo(MachineLoan.ACTIVE);
        assertThat(l.getEndsGameTime()).isEqualTo(l.getDeliveredGameTime() + 2 * DAY);
        assertThat(mails("DEALER_DEMO_DELIVERED")).hasSize(1);
        assertThat(loaned.ids(sg)).containsExactly("veh_demo");
        // independent of R31-A2: the workshop's demo and the neighbours' loans do not see it
        assertThat(machineLoans.running(sg)).isEmpty();
        assertThat(machineLoans.list(sg)).isEmpty();
        assertThat(controller.demo().running().untilGameTime()).isEqualTo(l.getEndsGameTime());
    }

    @Test
    void oneDemoAtATimeAndEachMachineOncePerGameYear() {
        controller.requestDemo(new DealerOrderController.DemoRequest(MachineCatalogTest.FENDT));
        assertThatThrownBy(() -> controller.requestDemo(new DealerOrderController.DemoRequest(MachineCatalogTest.CATROS)))
                .isInstanceOf(BusinessRuleException.class).hasMessage("Es läuft schon eine Vorführung.");
        MachineLoan l = loans.findById(of(InstructionType.VEHICLE_SPAWN).getLast().getRelatedEntityId()).orElseThrow();
        l.setStatus(MachineLoan.RETURNED);
        assertThatThrownBy(() -> controller.requestDemo(new DealerOrderController.DemoRequest(MachineCatalogTest.FENDT)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Diese Maschine wurde in diesem Spieljahr schon vorgeführt.");
        assertThat(controller.demo().demonstratedThisYear()).containsExactly(MachineCatalogTest.FENDT);
        assertThat(controller.demo().last().machine()).isEqualTo("Fendt 700 Vario");
        controller.requestDemo(new DealerOrderController.DemoRequest(MachineCatalogTest.CATROS));
    }

    @Test
    void afterTheDemoTimeTheDealerTakesTheMachineBack() {
        MachineLoan l = delivered();
        sg.setCurrentGameTime(l.getEndsGameTime());
        service.tick(sg);
        OutboxInstruction remove = of(InstructionType.VEHICLE_REMOVE).getLast();
        assertThat(json.readTree(remove.getPayloadJson()).path("vehicleId").asString()).isEqualTo("veh_demo");
        ack(remove, "APPLIED", null, Map.of());
        assertThat(loans.findById(l.getId()).orElseThrow().getStatus()).isEqualTo(MachineLoan.RETURNED);
        assertThat(mails("DEALER_DEMO_ENDED")).hasSize(1);
        assertThat(controller.demo().running()).isNull();
    }

    @Test
    void aCoupledMachineCostsFiveHundredPerLateDay() {
        MachineLoan l = delivered();
        sg.setCurrentGameTime(l.getEndsGameTime());
        service.tick(sg);
        ack(of(InstructionType.VEHICLE_REMOVE).getLast(), "FAILED", "VEHICLE_ATTACHED", Map.of());
        assertThat(controller.demo().running().lateReason()).isEqualTo("Fendt 700 Vario ist angekoppelt");
        assertThat(mails("DEALER_DEMO_LATE")).hasSize(1);
        int removes = of(InstructionType.VEHICLE_REMOVE).size();
        service.tick(sg); // no new attempt before the next game day
        assertThat(of(InstructionType.VEHICLE_REMOVE)).hasSize(removes);
        long t = (GameTime.dayIndex(sg.getCurrentGameTime()) + 1) * DAY;
        sg.setCurrentGameTime(t);
        service.onDay(new GameDayPassedEvent(sg.getId(), t / DAY, t));
        OutboxInstruction fee = of(InstructionType.MONEY_TRANSACTION).getLast();
        var p = json.readTree(fee.getPayloadJson());
        assertThat(p.path("reason").asString()).isEqualTo("VEHICLE_DEMO_LATE_FEE");
        assertThat(p.path("amount").asLong()).isEqualTo(-500);
        assertThat(of(InstructionType.VEHICLE_REMOVE)).hasSize(removes + 1);
        assertThat(loans.findById(l.getId()).orElseThrow().getLateDays()).isEqualTo(1);
    }

    @Test
    void aDisappearedDemoMachineCostsItsValueAndTrust() {
        MachineLoan l = delivered();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + DAY);
        export(null);
        service.tick(sg);
        l = loans.findById(l.getId()).orElseThrow();
        assertThat(l.getStatus()).isEqualTo(MachineLoan.LOST);
        assertThat(l.getCompensation()).isEqualTo(240000);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(l.getCharacter()))
                .anyMatch(t -> t.getReason() == TrustReason.DEALER_DEMO_LOST);
        assertThat(json.readTree(of(InstructionType.MONEY_TRANSACTION).getLast().getPayloadJson()).path("reason").asString())
                .isEqualTo("COMPENSATION");
    }

    @Test
    void noFreeShopPlacePostponesTheDemo() {
        controller.requestDemo(new DealerOrderController.DemoRequest(MachineCatalogTest.FENDT));
        ack(of(InstructionType.VEHICLE_SPAWN).getLast(), "FAILED", "NO_SPACE", Map.of());
        assertThat(mails("DEALER_DEMO_POSTPONED")).hasSize(1);
        long t = (GameTime.dayIndex(sg.getCurrentGameTime()) + 1) * DAY;
        sg.setCurrentGameTime(t);
        service.onDay(new GameDayPassedEvent(sg.getId(), t / DAY, t));
        assertThat(of(InstructionType.VEHICLE_SPAWN)).hasSize(2);
    }
}
