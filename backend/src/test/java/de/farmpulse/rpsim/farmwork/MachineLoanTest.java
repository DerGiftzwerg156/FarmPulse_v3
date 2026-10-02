package de.farmpulse.rpsim.farmwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.credit.CreditScoringService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MachineLoan;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.domain.VehicleDeal;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.repository.VehicleDealRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.vehicle.VehicleTradeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-A2: borrowed machines of neighbours and demo machines of the workshop (owner decisions 2026-10-02). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class MachineLoanTest {

    static final long DAY = GameTime.days(1);
    static final String CONTEXT = """
            { "savegameId": "%s", "mapName": "Erlengrund", "sellPoints": [], "fillTypes": [], "farmlands": [],
              "storeVehicles": [
                { "xmlFilename": "data/vehicles/fendt/vario700.xml", "name": "Fendt 700 Vario", "price": 245000,
                  "lifetime": 600, "categoryName": "TRACTORSL", "isMod": false, "motorized": true },
                { "xmlFilename": "data/vehicles/deutz/series5.xml", "name": "Deutz-Fahr Serie 5", "price": 98000,
                  "lifetime": 600, "categoryName": "TRACTORSM", "isMod": false, "motorized": true },
                { "xmlFilename": "data/vehicles/claas/lexion.xml", "name": "CLAAS LEXION", "price": 380000,
                  "lifetime": 600, "categoryName": "HARVESTERS", "isMod": false, "motorized": true },
                { "xmlFilename": "data/vehicles/amazone/catros.xml", "name": "Amazone Catros", "price": 32000,
                  "lifetime": 600, "categoryName": "CULTIVATORS", "isMod": false, "motorized": false } ] }""";

    @Autowired Fixtures fx;
    @Autowired MachineLoanService loans;
    @Autowired LoanedVehicles loaned;
    @Autowired VehicleTradeService vehicleTrade;
    @Autowired NegotiationEngine engine;
    @Autowired ContractActions actions;
    @Autowired FailedInstructionService failed;
    @Autowired CreditScoringService scoring;
    @Autowired FactsService facts;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired ServiceCaseRepository cases;
    @Autowired VehicleDealRepository deals;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    Character workshop;
    Character jansen;

    @BeforeEach
    void setUp() {
        sg = fx.savegame(); // game time 10 days
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        sg.setMarketContextJson(CONTEXT.formatted(sg.getBridgeSavegameId()));
        workshop = fx.character(sg, CharacterRole.WORKSHOP, CharacterCategory.MANDATORY, "Meister Lüdtke");
        jansen = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bauer Jansen");
        jansen.setNeighborRole("DAIRY");
        snapshot(1_000_000, null);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setMachineLoan(new RpsimProperties.MachineLoan());
    }

    /** Facts at the current game time; {@code loanVehicle} = JSON of the borrowed machine in assets.vehicles. */
    private void snapshot(long balance, String loanVehicle) {
        String doc = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), balance);
        if (loanVehicle != null) {
            doc = doc.replace("\"condition\": 82 }]", "\"condition\": 82 }, " + loanVehicle + "]");
        }
        fx.snapshot(sg, sg.getCurrentGameTime(), balance, doc);
    }

    private static String vehicle(String id, double value, double condition) {
        return "{ \"uniqueId\": \"" + id + "\", \"value\": " + value + ", \"condition\": " + condition + " }";
    }

    private List<OutboxInstruction> of(InstructionType type) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == type).toList();
    }

    private JsonNode payload(OutboxInstruction o) {
        return json.readTree(o.getPayloadJson());
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private void applied(OutboxInstruction o, Map<String, Object> result) {
        o.setStatus(InstructionStatus.APPLIED);
        loans.onAck(new BridgeEvents.InstructionAcked(sg.getId(), o.getInstructionId(), "APPLIED",
                o.getRelatedEntityType(), o.getRelatedEntityId(), result));
    }

    private void fail(OutboxInstruction o, String message) {
        o.setStatus(InstructionStatus.FAILED);
        o.setAckMessage(message);
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), o.getInstructionId(), "FAILED",
                o.getRelatedEntityType(), o.getRelatedEntityId()));
    }

    private void nextDay() {
        sg.setCurrentGameTime(sg.getCurrentGameTime() + DAY);
        loans.onDay(new GameDayPassedEvent(sg.getId(), GameTime.dayIndex(sg.getCurrentGameTime()), sg.getCurrentGameTime()));
    }

    private List<Long> rents() {
        return of(InstructionType.MONEY_TRANSACTION).stream()
                .filter(o -> "MACHINE_RENT".equals(payload(o).get("reason").asString()))
                .map(o -> payload(o).get("amount").asLong()).toList();
    }

    /** Borrows the Fendt for {@code days} days and delivers it (ack with veh_loan). */
    private MachineLoan borrowedFendt(int days) {
        MachineLoan l = loans.borrow(sg, jansen.getId(), "data/vehicles/fendt/vario700.xml", days);
        applied(of(InstructionType.VEHICLE_SPAWN).getLast(), Map.of("vehicleId", "veh_loan"));
        return l;
    }

    // ------------------------------------------------------------------------------------------ loan

    @Test
    void aNeighbourLendsMachinesOfHisRoleAtTheDailyRent() {
        List<MachineLoanService.Choice> choices = loans.loanChoices(sg, jansen.getId());
        // DAIRY: tractors, forage harvesters and mowers - no combine, no cultivator
        assertThat(choices).extracting(MachineLoanService.Choice::name)
                .containsExactlyInAnyOrder("Fendt 700 Vario", "Deutz-Fahr Serie 5");
        assertThat(choices).filteredOn(c -> c.name().equals("Fendt 700 Vario"))
                .singleElement().satisfies(c -> assertThat(c.dailyRent()).isEqualTo(735)); // 245,000 x 0.3 %
        assertThat(loans.loanChoices(sg, jansen.getId())).isEqualTo(choices); // the same all day
        assertThat(loans.dailyRent(245_000, null)).isEqualTo(735);
    }

    @Test
    void aBorrowedMachineComesWithPriceZeroAndTheRentRunsEveryGameDay() {
        MachineLoan l = loans.borrow(sg, jansen.getId(), "data/vehicles/fendt/vario700.xml", 2);
        assertThat(l.getStatus()).isEqualTo(MachineLoan.DELIVERING);
        OutboxInstruction spawn = of(InstructionType.VEHICLE_SPAWN).getFirst();
        assertThat(payload(spawn).get("price").asLong()).isZero();
        assertThat(payload(spawn).get("moneyReason").asString()).isEqualTo("MACHINE_RENT");
        assertThat(payload(spawn).get("ageMonths").asInt()).isBetween(12, 120);
        assertThatThrownBy(() -> loans.borrow(sg, jansen.getId(), "data/vehicles/deutz/series5.xml", 1))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("schon eine Leih");

        applied(spawn, Map.of("vehicleId", "veh_loan"));
        assertThat(l.getStatus()).isEqualTo(MachineLoan.ACTIVE);
        assertThat(l.getVehicleId()).isEqualTo("veh_loan");
        assertThat(rents()).containsExactly(-735L); // the first day
        assertThat(narrations()).contains("MACHINE_LOAN_DELIVERED");

        snapshot(1_000_000, vehicle("veh_loan", 200_000, l.getStartCondition()));
        nextDay();
        assertThat(rents()).containsExactly(-735L, -735L);
        assertThat(of(InstructionType.VEHICLE_REMOVE)).isEmpty();
        nextDay(); // the agreed 2 days are over
        OutboxInstruction remove = of(InstructionType.VEHICLE_REMOVE).getFirst();
        assertThat(payload(remove).get("vehicleId").asString()).isEqualTo("veh_loan");
        assertThat(l.getStatus()).isEqualTo(MachineLoan.RETURNING);
        applied(remove, Map.of());
        assertThat(l.getStatus()).isEqualTo(MachineLoan.RETURNED);
        assertThat(l.getCompensation()).isNull();
        assertThat(narrations()).contains("MACHINE_LOAN_RETURNED");
    }

    @Test
    void theBorrowedMachineIsNoAssetOfTheFarm(@Autowired de.farmpulse.rpsim.contract.MaintenanceService maintenance) {
        MachineLoan l = borrowedFendt(3);
        long feeWithoutLoan = maintenance.monthlyFee(sg);
        snapshot(1_000_000, vehicle("veh_loan", 200_000, l.getStartCondition()));
        assertThat(maintenance.monthlyFee(sg)).isEqualTo(feeWithoutLoan); // the maintenance fee ignores it
        var f = facts.latest(sg).orElseThrow();
        assertThat(loaned.ids(sg)).containsExactly("veh_loan");
        assertThat(vehicleTrade.ownVehicles(sg)).extracting(v -> v.uniqueId()).containsExactly("veh_00042");
        assertThat(facts.totalAssetValue(f) - scoring.totalAssets(sg)).isCloseTo(200_000,
                org.assertj.core.data.Offset.offset(0.5));
        assertThatThrownBy(() -> vehicleTrade.offerForSale(sg, "veh_loan", 100_000))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void aLateReturnCostsRentPlusSurchargeAndARemindertInTheGame() {
        MachineLoan l = borrowedFendt(1);
        snapshot(1_000_000, vehicle("veh_loan", 200_000, l.getStartCondition()));
        nextDay();
        fail(of(InstructionType.VEHICLE_REMOVE).getFirst(), "VEHICLE_IN_USE");
        assertThat(l.getStatus()).isEqualTo(MachineLoan.ACTIVE);
        assertThat(of(InstructionType.NOTIFICATION)).anySatisfy(n ->
                assertThat(payload(n).get("text").asString()).contains("abstellen"));
        nextDay();
        assertThat(rents()).containsExactly(-735L, -1103L); // rent + 50 %
        assertThat(l.getLateDays()).isEqualTo(1);
        assertThat(of(InstructionType.VEHICLE_REMOVE)).hasSize(2);
    }

    @Test
    void lostConditionCostsACompensationAndTrust() {
        MachineLoan l = borrowedFendt(1);
        double start = l.getStartCondition();
        snapshot(1_000_000, vehicle("veh_loan", 180_000, start - 20));
        nextDay();
        applied(of(InstructionType.VEHICLE_REMOVE).getFirst(), Map.of());
        long expected = Math.round(20 / 100.0 * 245_000 * 0.5);
        assertThat(l.getCompensation()).isEqualTo(expected);
        assertThat(of(InstructionType.MONEY_TRANSACTION)).anySatisfy(o -> {
            assertThat(payload(o).get("reason").asString()).isEqualTo("COMPENSATION");
            assertThat(payload(o).get("amount").asLong()).isEqualTo(-expected);
        });
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(jansen)).extracting(TrustEvent::getReason)
                .contains(TrustReason.MACHINE_LOAN_DAMAGE);
    }

    @Test
    void aMissedRentEndsTheLoanAtOnce() {
        MachineLoan l = borrowedFendt(3);
        fail(of(InstructionType.MONEY_TRANSACTION).getFirst(), "INSUFFICIENT_FUNDS");
        assertThat(l.getStatus()).isEqualTo(MachineLoan.RETURNING);
        assertThat(l.getEndReason()).isEqualTo("RENT_MISSED");
        assertThat(of(InstructionType.VEHICLE_REMOVE)).hasSize(1);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(jansen)).extracting(TrustEvent::getReason)
                .contains(TrustReason.MACHINE_LOAN_RENT_MISSED);
        assertThat(narrations()).contains("MACHINE_LOAN_RECALLED");
    }

    @Test
    void theRentForAllDaysMustBeAvailable() {
        snapshot(2_000, null);
        assertThatThrownBy(() -> loans.borrow(sg, jansen.getId(), "data/vehicles/fendt/vario700.xml", 3))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("2205");
        assertThatThrownBy(() -> loans.borrow(sg, jansen.getId(), "data/vehicles/fendt/vario700.xml", 9))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Spieltage");
        assertThatThrownBy(() -> loans.borrow(sg, jansen.getId(), "data/vehicles/claas/lexion.xml", 1))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nicht zu verleihen");
    }

    @Test
    void aMachineThatDisappearsCostsItsGameValue() {
        MachineLoan l = borrowedFendt(3);
        snapshot(1_000_000, vehicle("veh_loan", 190_000, l.getStartCondition()));
        nextDay();
        assertThat(l.getLastValue()).isEqualTo(190_000);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.hours(2));
        snapshot(1_000_000, null); // sold in the game shop
        nextDay();
        assertThat(l.getStatus()).isEqualTo(MachineLoan.LOST);
        assertThat(of(InstructionType.MONEY_TRANSACTION)).anySatisfy(o ->
                assertThat(payload(o).get("amount").asLong()).isEqualTo(-190_000));
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(jansen)).extracting(TrustEvent::getReason)
                .contains(TrustReason.MACHINE_LOAN_LOST);
    }

    @Test
    void noSpaceOnTheFarmMeansANewDeliveryTheNextDay() {
        loans.borrow(sg, jansen.getId(), "data/vehicles/fendt/vario700.xml", 1);
        fail(of(InstructionType.VEHICLE_SPAWN).getFirst(), "NO_SPACE");
        assertThat(of(InstructionType.NOTIFICATION)).hasSize(1);
        nextDay();
        assertThat(of(InstructionType.VEHICLE_SPAWN)).hasSize(2);
        assertThat(rents()).isEmpty(); // nothing before the delivery
    }

    // ------------------------------------------------------------------------------------------ demo

    @Test
    void aDemoIsFreeAndEndsWithAPurchaseOfferThatKeepsTheMachine() {
        List<MachineLoanService.Choice> choices = loans.demoChoices(sg);
        assertThat(choices).extracting(MachineLoanService.Choice::name).doesNotContain("Amazone Catros"); // no engine
        MachineLoan l = loans.requestDemo(sg, "data/vehicles/claas/lexion.xml");
        OutboxInstruction spawn = of(InstructionType.VEHICLE_SPAWN).getFirst();
        assertThat(payload(spawn).get("ageMonths").asInt()).isZero();
        assertThat(payload(spawn).get("operatingHours").asInt()).isZero();
        assertThat(payload(spawn).get("price").asLong()).isZero();
        applied(spawn, Map.of("vehicleId", "veh_demo"));
        assertThat(l.getDays()).isBetween(1, 2);
        assertThat(rents()).isEmpty();
        snapshot(1_000_000, vehicle("veh_demo", 360_000, 100));
        for (int i = 0; i < l.getDays(); i++) {
            nextDay();
        }
        assertThat(l.getStatus()).isEqualTo(MachineLoan.PURCHASE_OFFER);
        VehicleDeal d = deals.findById(l.getVehicleDealId()).orElseThrow();
        assertThat(d.getDemoLoanId()).isEqualTo(l.getId());
        assertThat(d.getBasePrice()).isEqualTo(342_000); // list price - 10 %
        assertThat(narrations()).contains("MACHINE_DEMO_PURCHASE_OFFER");

        var n = vehicleTrade.negotiationsOf(sg, d).getFirst();
        engine.placeOffer(sg, n.getId(), 342_000);
        OutboxInstruction money = of(InstructionType.MONEY_TRANSACTION).getLast();
        assertThat(payload(money).get("reason").asString()).isEqualTo("VEHICLE_PURCHASE");
        assertThat(payload(money).get("amount").asLong()).isEqualTo(-342_000);
        assertThat(of(InstructionType.VEHICLE_SPAWN)).hasSize(1); // no second delivery
        applied(money, Map.of());
        assertThat(l.getStatus()).isEqualTo(MachineLoan.PURCHASED);
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.DONE);
        assertThat(loaned.ids(sg)).isEmpty(); // now an own machine
    }

    @Test
    void aDemoWithoutAPurchaseGoesBack() {
        MachineLoan l = loans.requestDemo(sg, "data/vehicles/claas/lexion.xml");
        applied(of(InstructionType.VEHICLE_SPAWN).getFirst(), Map.of("vehicleId", "veh_demo"));
        snapshot(1_000_000, vehicle("veh_demo", 360_000, 100));
        for (int i = 0; i < l.getDays(); i++) {
            nextDay();
        }
        VehicleDeal d = deals.findById(l.getVehicleDealId()).orElseThrow();
        engine.withdraw(sg, vehicleTrade.negotiationsOf(sg, d).getFirst().getId());
        vehicleTrade.closeIfEnded(sg, d);
        nextDay();
        assertThat(l.getStatus()).isEqualTo(MachineLoan.RETURNING);
        assertThat(l.getEndReason()).isEqualTo("NOT_BOUGHT");
        applied(of(InstructionType.VEHICLE_REMOVE).getFirst(), Map.of());
        assertThat(l.getStatus()).isEqualTo(MachineLoan.RETURNED);
        assertThat(l.getCompensation()).isNull(); // no compensation for a demo
    }

    @Test
    void theWorkshopOffersADemoThatComesOnlyAfterTheAnswer() {
        props.getFormulas().getMachineLoan().setDemoOfferProbability(1);
        loans.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        loans.onMonth(new GameMonthPassedEvent(sg.getId(), 2, sg.getCurrentGameTime())); // one open offer at most
        List<ServiceCase> offers = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.MACHINE_DEMO_OFFER));
        assertThat(offers).hasSize(1);
        assertThat(of(InstructionType.VEHICLE_SPAWN)).isEmpty();
        assertThat(narrations()).contains("MACHINE_DEMO_OFFER");
        actions.acceptCase(sg, offers.getFirst().getId());
        assertThat(offers.getFirst().getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(of(InstructionType.VEHICLE_SPAWN)).hasSize(1);
        assertThat(loans.running(sg)).hasValueSatisfying(l -> assertThat(l.getKind()).isEqualTo(MachineLoan.DEMO));
    }

    @Test
    void anUnansweredDemoOfferExpires() {
        props.getFormulas().getMachineLoan().setDemoOfferProbability(1);
        loans.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        ServiceCase offer = cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.MACHINE_DEMO_OFFER)).getFirst();
        for (int i = 0; i < 6; i++) {
            nextDay();
        }
        assertThat(offer.getStatus()).isEqualTo(CaseStatus.EXPIRED);
    }
}
