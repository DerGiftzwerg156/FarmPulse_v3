package de.farmpulse.rpsim.dealer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.api.DealerOrderController;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.DealerDelivery;
import de.farmpulse.rpsim.domain.DealerOrder;
import de.farmpulse.rpsim.domain.DealerTradeIn;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.DealerDeliveryRepository;
import de.farmpulse.rpsim.repository.DealerOrderRepository;
import de.farmpulse.rpsim.repository.DealerTradeInRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
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

/**
 * Roadmap vehicle dealer RFH-B / L (owner decisions 2026-10-15/16): request from the cart, the offer at the next game
 * day (net price, trade-in by trust, hidden floor), the negotiation, 30 % deposit, the delivery date, VEHICLE_DELIVERY
 * with rest, delivery costs and express, a failed delivery waiting at the dealer, "Lieferung erneut versuchen" with
 * 500 EUR, a lost or damaged trade-in, the delay and the cancellation (50 % / 100 %).
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class DealerOrderTest {

    static final long DAY = GameTime.MS_PER_DAY;

    @Autowired Fixtures fx;
    @Autowired DealerOrderController controller;
    @Autowired DealerOrderService service;
    @Autowired DealerCartService cart;
    @Autowired MachineDealerService dealers;
    @Autowired SavegameContext context;
    @Autowired SavegameRepository savegames;
    @Autowired DealerOrderRepository orders;
    @Autowired DealerDeliveryRepository deliveries;
    @Autowired DealerTradeInRepository tradeIns;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired FailedInstructionService failed;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    double delayProbability;

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
        delayProbability = cfg().getDelayProbability();
        cfg().setDelayProbability(0);
        dealers.open(sg);
        export(500000, 1000L);
    }

    @AfterEach
    void tearDown() {
        cfg().setDelayProbability(delayProbability);
    }

    private RpsimProperties.MachineDealer cfg() {
        return props.getFormulas().getMachineDealer();
    }

    /** An export with the own machine "Fendt 312" (trade-in) at the given repair price. */
    private void export(long balance, Long repairPrice) {
        export(balance, repairPrice, true);
    }

    private void export(long balance, Long repairPrice, boolean withTradeIn) {
        String vehicles = withTradeIn ? "[{ \"uniqueId\": \"veh_00007\", \"value\": 40000, \"condition\": 90,"
                + " \"name\": \"Fendt 312\"" + (repairPrice == null ? "" : ", \"repairPrice\": " + repairPrice) + " }]" : "[]";
        String facts = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), balance)
                .replace("[{ \"uniqueId\": \"veh_00042\", \"value\": 285000, \"condition\": 82 }]", vehicles);
        fx.snapshot(sg, sg.getCurrentGameTime(), balance, facts);
    }

    private void fillCart() {
        cart.add(sg, MachineCatalogTest.FENDT, Map.of("baseColor", 2)); // 245.000 + 2.500
        cart.add(sg, MachineCatalogTest.CATROS, Map.of());              // 32.000
    }

    private DealerOrderController.OrderRequest together(boolean express, String... tradeIns) {
        return new DealerOrderController.OrderRequest(true, List.of(tradeIns),
                new DealerOrderController.ChoiceRequest(null, express), null);
    }

    private void nextDay() {
        long t = (GameTime.dayIndex(sg.getCurrentGameTime()) + 1) * DAY;
        sg.setCurrentGameTime(t);
        service.onDay(new GameDayPassedEvent(sg.getId(), t / DAY, t));
    }

    private DealerOrderController.OrderView only() {
        return controller.list().orders().getFirst();
    }

    private List<OutboxInstruction> of(InstructionType type) {
        return outbox.findBySavegameAndTypeOrderByIdAsc(sg, type);
    }

    private void ack(OutboxInstruction ins, String status, String message, Map<String, Object> result) {
        ins.setStatus("APPLIED".equals(status) ? InstructionStatus.APPLIED : InstructionStatus.FAILED);
        ins.setAckMessage(message);
        BridgeEvents.InstructionAcked e = new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), status,
                ins.getRelatedEntityType(), ins.getRelatedEntityId(), result);
        service.onAck(e);
        failed.onAck(e);
    }

    /** Request, offer, agreement at the floor and the deposit booked: the order is ORDERED. */
    private DealerOrder order(boolean express) {
        fillCart();
        controller.request(together(express, "veh_00007"));
        nextDay();
        DealerOrder o = orders.findBySavegameOrderByIdDesc(sg).getFirst();
        controller.bid(o.getId(), new DealerOrderController.BidRequest(o.getFloorPrice() - 100));
        controller.accept(o.getId());
        ack(of(InstructionType.MONEY_TRANSACTION).getLast(), "APPLIED", null, Map.of());
        return orders.findById(o.getId()).orElseThrow();
    }

    @Test
    void theCartBecomesARequestAndTheDealerAnswersTheNextGameDay() {
        fillCart();
        assertThat(controller.tradeInCandidates()).extracting(DealerOrderController.TradeInCandidateView::name)
                .containsExactly("Fendt 312");
        var v = controller.request(together(false, "veh_00007"));
        assertThat(cart.positions(sg)).isEmpty();
        var o = v.orders().getFirst();
        assertThat(o.status()).isEqualTo(DealerOrder.REQUESTED);
        assertThat(o.listTotal()).isEqualTo(279500);
        assertThat(o.deliveries()).hasSize(1);
        assertThat(o.deliveries().getFirst().deliveryFee()).isEqualTo(250); // shop place: base fee (L6)
        // the trade-in is bound to the running request
        assertThat(controller.tradeInCandidates()).isEmpty();

        nextDay();
        o = only();
        assertThat(o.status()).isEqualTo(DealerOrder.OFFERED);
        // trust-linear share of 90-100 % of the game value
        assertThat(o.tradeInOffer()).isBetween(36000L, 40000L);
        assertThat(o.offerPrice()).isEqualTo(279500 - o.tradeInOffer());
        assertThat(o.depositAtAgreement()).isEqualTo(Math.round(o.offerPrice() * 0.3));
        assertThat(o.validUntilGameTime()).isEqualTo(sg.getCurrentGameTime() + 7 * DAY);
        assertThat(o.negotiation().roundsUsed()).isZero();
        assertThat(o.openAmount()).isEqualTo(o.offerPrice() + 250);
        // the floor: list price with at most 15 % discount (or the trust formula) minus 100 % of the game value
        DealerOrder entity = orders.findById(o.id()).orElseThrow();
        assertThat(entity.getFloorPrice()).isGreaterThanOrEqualTo(Math.round(279500 * 0.85) - 40000);
    }

    @Test
    void tradeInsWorthMoreThanTheOrderAreRefused() {
        cart.add(sg, MachineCatalogTest.CATROS, Map.of());
        assertThatThrownBy(() -> controller.request(together(false, "veh_00007")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Die Inzahlungsmaschinen sind mehr wert als die Bestellung – bitte weniger in Zahlung geben.");
        assertThat(cart.positions(sg)).hasSize(1);
    }

    @Test
    void noAgreementBringsThePositionsBackIntoTheCart() {
        fillCart();
        controller.request(together(false));
        nextDay();
        long id = only().id();
        var v = controller.decline(id);
        assertThat(v.orders().getFirst().status()).isEqualTo(DealerOrder.REJECTED);
        v = controller.backToCart(id);
        assertThat(v.orders().getFirst().returnedToCart()).isTrue();
        assertThat(cart.positions(sg)).hasSize(2);
        assertThatThrownBy(() -> controller.backToCart(id)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void theAgreementBooksTheDepositAndPlansTheDelivery() {
        DealerOrder o = order(false);
        assertThat(o.getStatus()).isEqualTo(DealerOrder.ORDERED);
        assertThat(o.getAgreedPrice()).isEqualTo(o.getFloorPrice());
        OutboxInstruction deposit = of(InstructionType.MONEY_TRANSACTION).getLast();
        JsonNode p = json.readTree(deposit.getPayloadJson());
        assertThat(p.path("reason").asString()).isEqualTo("VEHICLE_DEPOSIT");
        assertThat(p.path("amount").asLong()).isEqualTo(-Math.round(o.getAgreedPrice() * 0.3));
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        assertThat(d.getStatus()).isEqualTo(DealerDelivery.PLANNED);
        assertThat(d.isCarriesTradeIns()).isTrue();
        // tractor 5-10 days + colour 1 day (L1/L2), never before 9:00 the next game day
        assertThat(d.getDueGameTime()).isBetween(d.getAgreedGameTime() + 5 * DAY, d.getAgreedGameTime() + 11 * DAY);
        assertThat(tradeIns.findByOrderIdOrderByIdAsc(o.getId()).getFirst().getRepairPriceAtAgreement()).isEqualTo(1000);
        var view = only();
        assertThat(view.depositPaid()).isTrue();
        assertThat(view.deliveries().getFirst().cancellable()).isTrue();
        assertThat(view.deliveries().getFirst().rest()).isEqualTo(o.getAgreedPrice() - o.getDeposit());
    }

    @Test
    void aRefusedDepositMeansNoOrder() {
        fillCart();
        controller.request(together(false));
        nextDay();
        DealerOrder o = orders.findBySavegameOrderByIdDesc(sg).getFirst();
        controller.accept(o.getId());
        ack(of(InstructionType.MONEY_TRANSACTION).getLast(), "FAILED", "INSUFFICIENT_FUNDS", Map.of());
        assertThat(only().status()).isEqualTo(DealerOrder.FAILED);
        assertThat(deliveries.findByOrderIdOrderByIdAsc(o.getId())).allMatch(d -> d.getStatus().equals(DealerDelivery.CANCELLED));
    }

    @Test
    void onTheDateTheDeliveryBooksRestFeesAndExpressAndTakesTheTradeIn() {
        DealerOrder o = order(true);
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        sg.setCurrentGameTime(d.getDueGameTime());
        export(500000, 4000L); // more damage since the agreement: 3.000 deducted (B4)
        service.tick(sg);
        OutboxInstruction ins = of(InstructionType.VEHICLE_DELIVERY).getLast();
        JsonNode p = json.readTree(ins.getPayloadJson());
        assertThat(p.path("items")).hasSize(2);
        assertThat(p.path("items").get(0).path("configurations").path("baseColor").path("index").asInt()).isEqualTo(2);
        assertThat(p.path("fallbackToShop").asBoolean()).isTrue();
        assertThat(p.has("place")).isFalse();
        assertThat(p.path("tradeIns").get(0).asString()).isEqualTo("veh_00007");
        long rest = o.getAgreedPrice() - o.getDeposit() + 3000;
        assertThat(p.path("bookings").get(0).path("moneyReason").asString()).isEqualTo("VEHICLE_NEW_PURCHASE");
        assertThat(p.path("bookings").get(0).path("amount").asLong()).isEqualTo(rest);
        assertThat(p.path("bookings").get(1).path("moneyReason").asString()).isEqualTo("VEHICLE_DELIVERY_FEE");
        assertThat(p.path("bookings").get(1).path("amount").asLong()).isEqualTo(250);
        assertThat(p.path("bookings").get(2).path("moneyReason").asString()).isEqualTo("VEHICLE_EXPRESS_FEE");
        assertThat(p.path("bookings").get(2).path("amount").asLong()).isEqualTo(Math.round(247500 * 0.05) + 1600);

        ack(ins, "APPLIED", null, Map.of("placeUsed", "SHOP", "vehicleCount", 2, "vehicleId1", "veh_100",
                "vehicleId2", "veh_101", "tradeInsRemoved", 1));
        d = deliveries.findById(d.getId()).orElseThrow();
        assertThat(d.getStatus()).isEqualTo(DealerDelivery.DELIVERED);
        assertThat(d.getVehicleIds()).isEqualTo("veh_100,veh_101");
        assertThat(d.getPlaceName()).isEqualTo("Shop-Platz des Spiels");
        assertThat(only().status()).isEqualTo(DealerOrder.COMPLETED);
        assertThat(tradeIns.findByOrderIdOrderByIdAsc(o.getId()).getFirst().getStatus()).isEqualTo(DealerTradeIn.REMOVED);
        // the in-game hint
        assertThat(of(InstructionType.NOTIFICATION)).anyMatch(n -> n.getPayloadJson()
                .contains("Deine neue Fendt 700 Vario, Amazone Catros steht bei Shop-Platz des Spiels"));
    }

    @Test
    void aFailedDeliveryWaitsAtTheDealerAndTheRetryCostsFiveHundred() {
        DealerOrder o = order(false);
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        sg.setCurrentGameTime(d.getDueGameTime());
        service.tick(sg);
        ack(of(InstructionType.VEHICLE_DELIVERY).getLast(), "FAILED", "TRADE_IN_ATTACHED: veh_00007", Map.of());
        var dv = only().deliveries().getFirst();
        assertThat(dv.status()).isEqualTo(DealerDelivery.WAITING);
        assertThat(dv.lastFailure()).isEqualTo("Fendt 312 ist angekoppelt");
        assertThat(dv.retryable()).isTrue();

        controller.retry(dv.id());
        JsonNode p = json.readTree(of(InstructionType.VEHICLE_DELIVERY).getLast().getPayloadJson());
        assertThat(p.path("bookings").get(2).path("note").asString()).isEqualTo("Neuer Lieferversuch");
        assertThat(p.path("bookings").get(2).path("amount").asLong()).isEqualTo(500);
        // a second failure: waits again, no second mail; the next game day tries again without the fee
        ack(of(InstructionType.VEHICLE_DELIVERY).getLast(), "FAILED", "NO_SPACE", Map.of());
        assertThat(only().deliveries().getFirst().lastFailure()).isEqualTo("Shop-Platz belegt");
        int before = of(InstructionType.VEHICLE_DELIVERY).size();
        nextDay();
        assertThat(of(InstructionType.VEHICLE_DELIVERY)).hasSize(before + 1);
        p = json.readTree(of(InstructionType.VEHICLE_DELIVERY).getLast().getPayloadJson());
        assertThat(p.path("bookings").toString()).doesNotContain("Neuer Lieferversuch");
    }

    @Test
    void anOlderModWaitsWithModAktualisieren() {
        DealerOrder o = order(false);
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        sg.setCurrentGameTime(d.getDueGameTime());
        service.tick(sg);
        ack(of(InstructionType.VEHICLE_DELIVERY).getLast(), "REJECTED", "unknown type VEHICLE_DELIVERY", Map.of());
        assertThat(only().deliveries().getFirst().lastFailure()).isEqualTo("Mod aktualisieren");
    }

    @Test
    void aMissingMachineIsMissingMoney() {
        DealerOrder o = order(false);
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        sg.setCurrentGameTime(d.getDueGameTime());
        export(1000, 1000L);
        service.tick(sg);
        OutboxInstruction ins = of(InstructionType.VEHICLE_DELIVERY).getLast();
        long total = 0;
        for (JsonNode b : json.readTree(ins.getPayloadJson()).path("bookings")) {
            total += b.path("amount").asLong();
        }
        ack(ins, "FAILED", "INSUFFICIENT_FUNDS", Map.of());
        assertThat(only().deliveries().getFirst().lastFailure())
                .isEqualTo("Es fehlen " + GameTimeText.money(total - 1000 - LiquidityMin.of(sg)));
    }

    @Test
    void aTradeInSoldBeforeTheDeliveryAddsTheOfferedValueToTheRest() {
        DealerOrder o = order(false);
        DealerTradeIn t = tradeIns.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        export(500000, null, false);
        service.tick(sg);
        assertThat(tradeIns.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(DealerTradeIn.LOST);
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        assertThat(service.rest(sg, d)).isEqualTo(o.getAgreedPrice() - o.getDeposit() + t.getOfferValue());
    }

    @Test
    void cancellingRefundsHalfTheDepositAndAllOfItAfterADelay() {
        DealerOrder o = order(false);
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        assertThat(only().deliveries().getFirst().refund()).isEqualTo(Math.round(o.getDeposit() * 0.5));
        controller.cancel(d.getId());
        List<OutboxInstruction> money = of(InstructionType.MONEY_TRANSACTION);
        OutboxInstruction refund = money.get(money.size() - 2);
        OutboxInstruction fee = money.getLast();
        assertThat(refund.getBatchId()).isNotNull().isEqualTo(fee.getBatchId());
        assertThat(json.readTree(refund.getPayloadJson()).path("reason").asString()).isEqualTo("VEHICLE_REFUND");
        assertThat(json.readTree(refund.getPayloadJson()).path("amount").asLong()).isEqualTo(o.getDeposit());
        assertThat(json.readTree(fee.getPayloadJson()).path("reason").asString()).isEqualTo("VEHICLE_CANCELLATION_FEE");
        assertThat(json.readTree(fee.getPayloadJson()).path("amount").asLong())
                .isEqualTo(-(o.getDeposit() - Math.round(o.getDeposit() * 0.5)));
        assertThat(only().status()).isEqualTo(DealerOrder.CANCELLED);
        assertThatThrownBy(() -> controller.cancel(d.getId())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void aDelayMovesTheDateAndMakesTheCancellationFree() {
        cfg().setDelayProbability(1);
        DealerOrder o = order(false);
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        long due = d.getDueGameTime();
        sg.setCurrentGameTime(due - 1);
        service.tick(sg);
        d = deliveries.findById(d.getId()).orElseThrow();
        assertThat(d.isDelayed()).isTrue();
        assertThat(d.getDueGameTime()).isBetween(due + DAY - GameTime.MS_PER_HOUR * 9, due + 6 * DAY);
        assertThat(of(InstructionType.VEHICLE_DELIVERY)).isEmpty();
        assertThat(only().deliveries().getFirst().refund()).isEqualTo(o.getDeposit());
    }

    @Test
    void theDeliveryPointCanChangeUntilTheDeliveryIsSent() {
        DealerOrder o = order(false);
        DealerDelivery d = deliveries.findByOrderIdOrderByIdAsc(o.getId()).getFirst();
        assertThatThrownBy(() -> controller.changePlace(d.getId(), new DealerOrderController.PlaceRequest(999L)))
                .isInstanceOf(IllegalArgumentException.class);
        sg.setCurrentGameTime(d.getDueGameTime());
        service.tick(sg);
        assertThatThrownBy(() -> controller.changePlace(d.getId(), new DealerOrderController.PlaceRequest(null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    /** The overdraft floor of the savegame (0 without overdraft). */
    static final class LiquidityMin {
        static long of(Savegame sg) {
            return de.farmpulse.rpsim.bridge.LiquidityService.minBalance(sg);
        }
    }
}
