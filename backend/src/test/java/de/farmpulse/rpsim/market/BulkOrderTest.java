package de.farmpulse.rpsim.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.communication.CommunicationService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.BulkOrder;
import de.farmpulse.rpsim.domain.CallStatus;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Communication;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CommunicationInitiator;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.finance.LiquidityPlanService;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.CalendarChangedEvent;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.2 R32-G1..G4: requests of bulk buyers, instant delivery, delivery month, settlement. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class BulkOrderTest {

    static final long DAY = GameTime.days(1);

    @Autowired Fixtures fx;
    @Autowired BulkOrderService bulk;
    @Autowired ForwardContractService forwards;
    @Autowired LiquidityPlanService plan;
    @Autowired de.farmpulse.rpsim.tablet.CalendarPlanService calendar;
    @Autowired ContractActions actions;
    @Autowired FailedInstructionService failed;
    @Autowired CommunicationService communications;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired MarketEventRepository marketEvents;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    List<String> fillTypes;
    double callShare;

    @BeforeEach
    void setUp() {
        fillTypes = props.getFormulas().getBulkOrder().getFillTypes();
        callShare = props.getFormulas().getBulkOrder().getCallShare();
        sg = fx.savegame(); // game time 10 days = month index 10 with one day per period
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        // the dairy is a production of the map: it accepts wheat and names a price, but never orders in bulk
        sg.setMarketContextJson("""
                { "savegameId": "%s", "mapName": "Erlengrund",
                  "sellPoints": [{ "id": "MillNorth", "name": "Mühle Nord", "acceptedFillTypes": ["WHEAT"] },
                                 { "id": "MillSouth", "name": "Mühle Süd", "acceptedFillTypes": ["WHEAT"] },
                                 { "id": "Dairy", "name": "Molkerei", "acceptedFillTypes": ["WHEAT"], "production": true,
                                   "ownedByPlayer": false }],
                  "fillTypes": ["WHEAT"], "farmlands": [] }""".formatted(sg.getBridgeSavegameId()));
        facts(600_000);
    }

    @AfterEach
    void restore() {
        props.getFormulas().getBulkOrder().setFillTypes(fillTypes);
        props.getFormulas().getBulkOrder().setCallShare(callShare);
    }

    /** Wheat at 215 (MillNorth), 230 (MillSouth) and 260 (Dairy, a production), {@code stock} litres in the own silos. */
    private void facts(long stock) {
        long t = sg.getCurrentGameTime();
        fx.snapshot(sg, t, 100_000, """
            { "schemaVersion": 1, "gameTime": %d, "savegameId": "%s", "liquidity": { "balance": 100000 },
              "assets": { "vehicles": [], "placeables": [], "farmland": [], "animals": [],
                          "storage": [{ "fillType": "WHEAT", "amount": %d, "capacity": 800000 }] },
              "liabilities": { "vanillaLoan": { "active": false, "remainingAmount": 0 } },
              "prices": [{ "sellPoint": "MillNorth", "fillType": "WHEAT", "currentPrice": 215 },
                         { "sellPoint": "MillSouth", "fillType": "WHEAT", "currentPrice": 230 },
                         { "sellPoint": "Dairy", "fillType": "WHEAT", "currentPrice": 260 }],
              "calendar": { "period": 11, "dayInPeriod": 1, "daysPerPeriod": 1, "year": 1, "monotonicDay": %d },
              "tradeStorage": [{ "fillType": "WHEAT", "amount": %d, "freeCapacity": 200000 }] }"""
                .formatted(t, sg.getBridgeSavegameId(), stock, t / DAY, stock));
    }

    private ServiceCase request() {
        return bulk.spawnRequest(sg).orElseThrow();
    }

    private List<OutboxInstruction> instructions() {
        return outbox.findBySavegameOrderByIdAsc(sg);
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private List<TrustReason> trust(ServiceCase sc) {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(sc.getCharacter()).stream().map(TrustEvent::getReason)
                .filter(r -> r != TrustReason.INITIAL).toList();
    }

    private void ack(OutboxInstruction ins) {
        ins.setStatus(InstructionStatus.APPLIED);
        bulk.onAck(new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), "APPLIED",
                ins.getRelatedEntityType(), ins.getRelatedEntityId(), Map.of()));
    }

    // ------------------------------------------------------------------------------------------ G1

    @Test
    void aRequestComesFromANewBuyerOfASellPointOfTheMap() {
        for (int i = 0; i < 20; i++) {
            ServiceCase sc = request();
            assertThat(sc.getKind()).isEqualTo(CaseKind.BULK_ORDER);
            assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
            assertThat(sc.getReference()).isEqualTo("WHEAT");
            assertThat(sc.getExternalId()).isIn("MillNorth", "MillSouth"); // never the production
            assertThat(sc.getTitle()).isEqualTo(sc.getExternalId().equals("MillNorth") ? "Mühle Nord" : "Mühle Süd");
            assertThat(sc.getQuantity()).isBetween(50_000, 500_000);
            assertThat(sc.getQuantity() % 10_000).isZero();
            // instant price: best market price of all sell points (the dairy too) x 1.25
            assertThat(sc.getCostAmount()).isEqualTo(Math.round(260 * 1.25));
            assertThat(sc.getOfferAmount()).isEqualTo(Math.round(sc.getQuantity() / 1000.0 * sc.getCostAmount()));
            assertThat(sc.getDeadlineGameTime()).isEqualTo(sg.getCurrentGameTime() + 5 * DAY);
            assertThat(sc.getCharacter().getRole()).isEqualTo(CharacterRole.BULK_BUYER);
            assertThat(sc.getCharacter().getAffiliation()).isEqualTo(sc.getTitle());
        }
        // one new buyer per request
        assertThat(bulk.requests(sg)).extracting(c -> c.getCharacter().getId()).doesNotHaveDuplicates();
        assertThat(narrations()).hasSize(20).containsOnly("BULK_ORDER_REQUEST");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).allSatisfy(j -> assertThat(j.getFormLink()).startsWith("/handel?case="));
    }

    @Test
    void withoutAPriceOrAnAmountRangeThereIsNoRequest() {
        props.getFormulas().getBulkOrder().setFillTypes(List.of("BARLEY")); // no amount range and no price
        assertThat(bulk.spawnRequest(sg)).isEmpty();
        props.getFormulas().getBulkOrder().setFillTypes(List.of("WHEAT"));
        sg.setMarketContextJson(null); // no market context yet
        assertThat(bulk.spawnRequest(sg)).isEmpty();
    }

    @Test
    void aMissedCallBringsTheRequestAsMail() {
        props.getFormulas().getBulkOrder().setCallShare(1);
        ServiceCase sc = request();
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getChannel()).isEqualTo(Channel.CALL);
        Communication call = communications.create(new CommunicationService.Draft(sg, sc.getCharacter(), Channel.CALL,
                CommunicationInitiator.CHARACTER, "Großauftrag", "Hallo?", CommunicationCategory.TRADE,
                "BULK_ORDER_REQUEST", BulkOrderService.RELATED, sc.getId(), null, null, false, null));
        bulk.onCall(new CallService.CallStatusChanged(sg.getId(), call.getId(), CallStatus.MISSED));
        NarrationJob mail = jobs.findBySavegameOrderByIdAsc(sg).getLast();
        assertThat(mail.getEventType()).isEqualTo("BULK_ORDER_REQUEST");
        assertThat(mail.getChannel()).isEqualTo(Channel.MAIL);
        // an answered call or a closed request brings no mail
        bulk.onCall(new CallService.CallStatusChanged(sg.getId(), call.getId(), CallStatus.COMPLETED));
        actions.declineCase(sg, sc.getId());
        bulk.onCall(new CallService.CallStatusChanged(sg.getId(), call.getId(), CallStatus.DECLINED));
        assertThat(narrations()).hasSize(2);
    }

    @Test
    void refusalsMakeRequestsRarerAndTheBuyerLeaves() {
        ServiceCase first = request();
        actions.declineCase(sg, first.getId());
        assertThat(first.getStatus()).isEqualTo(CaseStatus.DECLINED);
        assertThat(sg.getBulkOrderFactor()).isEqualTo(0.75);
        assertThat(first.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);
        assertThat(first.getCharacter().getTerminationReason()).isEqualTo(TerminationReason.CONTRACT_ENDED);
        assertThat(trust(first)).isEmpty(); // refusing costs no trust

        ServiceCase second = request();
        sg.setCurrentGameTime(second.getDeadlineGameTime() + 1);
        bulk.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(second.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sg.getBulkOrderFactor()).isEqualTo(0.5625);
        assertThat(second.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);
        for (int i = 0; i < 12; i++) {
            actions.declineCase(sg, request().getId());
        }
        assertThat(sg.getBulkOrderFactor()).isEqualTo(0.1); // never below min-factor
    }

    // ------------------------------------------------------------------------------------------ G2

    @Test
    void anInstantDeliveryBooksTheGoodsOutAndPays() {
        sg.setBulkOrderFactor(0.75);
        ServiceCase sc = request();
        actions.acceptCase(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        List<OutboxInstruction> deal = instructions().stream()
                .filter(i -> sc.getId().equals(i.getRelatedEntityId())).toList();
        OutboxInstruction transfer = deal.stream().filter(i -> i.getType() == InstructionType.STORAGE_TRANSFER)
                .findFirst().orElseThrow();
        var p = json.readTree(transfer.getPayloadJson());
        assertThat(p.get("direction").asString()).isEqualTo("OUT");
        assertThat(p.get("amount").asLong()).isEqualTo(sc.getQuantity().longValue());
        assertThat(deal).anyMatch(i -> i.getPayloadJson().contains("GOODS_SALE")
                && i.getPayloadJson().contains("\"amount\":" + sc.getOfferAmount()));

        ack(transfer);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getResolution()).isEqualTo("DELIVERED");
        assertThat(sg.getBulkOrderFactor()).isEqualTo(1.0);
        assertThat(trust(sc)).containsExactly(TrustReason.BULK_ORDER_FULFILLED);
        assertThat(narrations()).containsExactly("BULK_ORDER_REQUEST", "BULK_ORDER_DELIVERED");
        assertThat(sc.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);
    }

    @Test
    void withoutTheWholeAmountInTheSiloOnlyADeliveryMonthIsPossible() {
        facts(40_000);
        ServiceCase sc = request();
        assertThatThrownBy(() -> actions.acceptCase(sg, sc.getId())).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("nicht genug").hasMessageContaining("Termin");
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(instructions()).isEmpty();
    }

    @Test
    void aRefusedTransferBooksNothingAndLeavesTheRequestOpen() {
        ServiceCase sc = request();
        actions.acceptCase(sg, sc.getId());
        assertThat(bulk.onInstructionFailed(sc.getId(), "INSUFFICIENT_STOCK")).isTrue();
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(sc.getCharacter().getStatus()).isEqualTo(CharacterStatus.ACTIVE);
        // a delivery month can still be agreed
        assertThat(bulk.agree(sg, sc.getId(), 2).getStatus()).isEqualTo(BulkOrder.OPEN);
    }

    // ------------------------------------------------------------------------------------------ G3

    @Test
    void theDeliveryMonthsNameTheFixedPriceOfTheSellPoint() {
        ServiceCase sc = request();
        double base = sc.getExternalId().equals("MillNorth") ? 215 : 230;
        List<BulkOrderService.MonthOption> months = bulk.months(sg, sc.getId());
        assertThat(months).hasSize(12);
        assertThat(months).extracting(BulkOrderService.MonthOption::leadMonths).startsWith(1, 2).endsWith(12);
        BulkOrderService.MonthOption three = months.get(2);
        assertThat(three.fixedPrice()).isEqualTo(Math.round(base * (1 + 0.05 + 0.03)));
        assertThat(months.get(11).fixedPrice()).isEqualTo(Math.round(base * (1 + 0.05 + 0.12)));
        assertThat(three.startGameTime()).isEqualTo(13 * DAY); // month index 10 + 3, a whole month
        assertThat(three.deadlineGameTime()).isEqualTo(14 * DAY);
        assertThat(three.period()).isEqualTo(2); // month index 13 = period 2 of the next year
        assertThat(three.expectedIncome()).isEqualTo(Math.round(sc.getQuantity() / 1000.0 * three.fixedPrice()));
        assertThat(months).allMatch(BulkOrderService.MonthOption::available);
    }

    @Test
    void aDeliveryMonthSetsTheFixedPriceForExactlyThatMonth() {
        ServiceCase sc = request();
        BulkOrder o = bulk.agree(sg, sc.getId(), 3);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(sc.getResolution()).isEqualTo("TERM_AGREED");
        assertThat(o.getQuantity()).isEqualTo(sc.getQuantity().longValue());
        assertThat(o.getDeliveryStartGameTime()).isEqualTo(13 * DAY);
        assertThat(o.getDeadlineGameTime()).isEqualTo(14 * DAY);

        OutboxInstruction price = outbox.findByInstructionId(o.getInstructionId()).orElseThrow();
        assertThat(price.getType()).isEqualTo(InstructionType.PRICE_EVENT);
        assertThat(price.getGameTimeEarliest()).isEqualTo(13 * DAY);
        var p = json.readTree(price.getPayloadJson());
        assertThat(p.get("priceMode").asString()).isEqualTo("FIXED");
        assertThat(p.get("sellPoint").asString()).isEqualTo(sc.getExternalId());
        assertThat(p.get("fixedPrice").asLong()).isEqualTo(o.getFixedPrice());
        assertThat(p.get("maxQuantity").asLong()).isEqualTo(sc.getQuantity().longValue());
        assertThat(p.get("deadlineGameTime").asLong()).isEqualTo(14 * DAY);

        OutboxInstruction notice = outbox.findByInstructionId(o.getNoticeInstructionId()).orElseThrow();
        assertThat(notice.getType()).isEqualTo(InstructionType.NOTIFICATION);
        assertThat(notice.getGameTimeEarliest()).isEqualTo(13 * DAY);
        assertThat(json.readTree(notice.getPayloadJson()).get("text").asString())
                .contains(sc.getTitle(), "wartet diesen Monat auf", String.format(java.util.Locale.GERMANY, "%,d", sc.getQuantity()));

        // no withdrawal, no second answer
        assertThatThrownBy(() -> bulk.agree(sg, sc.getId(), 4)).hasMessageContaining("nicht mehr offen");
        assertThatThrownBy(() -> actions.declineCase(sg, sc.getId())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void oneFixedPricePerSellPointAndFillTypeAndMonth() {
        ServiceCase first = request();
        String pair = first.getExternalId() + "|WHEAT";
        bulk.agree(sg, first.getId(), 3);
        // the forward contract is blocked on the whole pair, the event engine and the drought leave it alone
        assertThat(forwards.openPairs(sg)).contains(pair);
        assertThatThrownBy(() -> forwards.conclude(sg, "WHEAT", first.getExternalId(), 1_000, 5))
                .hasMessageContaining("Großauftrag");

        ServiceCase second = request();
        while (!second.getExternalId().equals(first.getExternalId())) {
            actions.declineCase(sg, second.getId());
            second = request();
        }
        Long secondId = second.getId();
        List<BulkOrderService.MonthOption> months = bulk.months(sg, secondId);
        assertThat(months.get(2).available()).isFalse();
        assertThat(months.get(2).reason()).isEqualTo("FIXED_PRICE_BUSY");
        assertThat(months.get(3).available()).isTrue();
        assertThatThrownBy(() -> bulk.agree(sg, secondId, 3)).hasMessageContaining("schon ein Festpreis");

        // a forward contract on the other sell point blocks its month there; a running special offer its months
        String other = first.getExternalId().equals("MillNorth") ? "MillSouth" : "MillNorth";
        forwards.conclude(sg, "WHEAT", other, 1_000, 5);
        MarketEvent offer = new MarketEvent();
        offer.setSavegame(sg);
        offer.setEventType(MarketEventType.SPECIAL_OFFER);
        offer.setStatus(MarketEventStatus.ACTIVE);
        offer.setFillType("WHEAT");
        offer.setSellPoint(first.getExternalId());
        offer.setStartGameTime(sg.getCurrentGameTime());
        offer.setEndGameTime(sg.getCurrentGameTime() + 2 * DAY); // months 10 and 11
        offer.setAnnouncedAtGameTime(sg.getCurrentGameTime());
        marketEvents.save(offer);
        assertThat(bulk.busyMonths(sg, first.getExternalId(), "WHEAT")).containsExactlyInAnyOrder(10L, 11L, 13L);
        assertThat(bulk.busyMonths(sg, other, "WHEAT")).containsExactly(15L);
        assertThat(bulk.months(sg, secondId).get(0).available()).isFalse(); // month 11
        assertThat(bulk.agree(sg, secondId, 2).getDeliveryStartGameTime()).isEqualTo(12 * DAY);
    }

    @Test
    void atMostThreeOpenOrdersWithADeliveryMonth() {
        for (int i = 1; i <= 3; i++) {
            ServiceCase sc = request();
            int lead = i;
            while (!bulk.months(sg, sc.getId()).get(lead - 1).available()) {
                lead++;
            }
            bulk.agree(sg, sc.getId(), lead);
        }
        Long fourth = request().getId();
        assertThatThrownBy(() -> bulk.agree(sg, fourth, 12)).hasMessageContaining("höchstens 3");
        assertThatThrownBy(() -> bulk.agree(sg, fourth, 13)).hasMessageContaining("Monate");
    }

    @Test
    void theLiquidityPlanShowsTheExpectedIncomeInTheDeliveryMonth() {
        BulkOrder o = bulk.agree(sg, request().getId(), 2);
        LiquidityPlanService.MonthPlan m = plan.plan(sg).months().get(1);
        assertThat(m.postings()).anySatisfy(x -> {
            assertThat(x.kind()).isEqualTo("BULK_ORDER");
            assertThat(x.amount()).isEqualTo(Math.round(o.getQuantity() / 1000.0 * o.getFixedPrice()));
            assertThat(x.estimate()).isTrue();
        });
        assertThat(plan.plan(sg).months().get(0).postings()).noneMatch(x -> x.kind().equals("BULK_ORDER"));
    }

    @Test
    void theCalendarShowsTheDeliveryMonth() {
        ServiceCase sc = request();
        BulkOrder o = bulk.agree(sg, sc.getId(), 1); // days 11..12, within the 7-day agenda
        var agenda = calendar.overview(sg).agenda();
        assertThat(agenda).anySatisfy(a -> {
            assertThat(a.kind()).isEqualTo("BULK_ORDER_START");
            assertThat(a.gameTime()).isEqualTo(11 * DAY);
            assertThat(a.title()).isEqualTo(sc.getTitle());
            assertThat(a.reference()).isEqualTo("WHEAT");
            assertThat(a.amount()).isEqualTo(Math.round(o.getQuantity() / 1000.0 * o.getFixedPrice()));
        });
        assertThat(agenda).anySatisfy(a -> {
            assertThat(a.kind()).isEqualTo("BULK_ORDER_END");
            assertThat(a.gameTime()).isEqualTo(12 * DAY);
            assertThat(a.amount()).isEqualTo(o.getQuantity());
        });
    }

    // ------------------------------------------------------------------------------------------ G4

    @Test
    void aShortfallCostsAQuarterOfTheFixedPriceAndAFullDeliveryGainsTrust() {
        ServiceCase first = request();
        BulkOrder shortOrder = bulk.agree(sg, first.getId(), 1);
        long ordered = shortOrder.getQuantity();
        bulk.onContractReported(new BridgeEvents.ContractReported(sg.getId(), shortOrder.getInstructionId(),
                ordered - 20_000, ordered, "DEADLINE_REACHED"));
        assertThat(shortOrder.getStatus()).isEqualTo(BulkOrder.SHORTFALL);
        long penalty = Math.round(20 * shortOrder.getFixedPrice() * 0.25);
        assertThat(shortOrder.getPenalty()).isEqualTo(penalty);
        assertThat(instructions().getLast().getPayloadJson()).contains("CONTRACT_PENALTY", "-" + penalty);
        assertThat(trust(first)).containsExactly(TrustReason.BULK_ORDER_SHORTFALL);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(first.getCharacter()).getLast().getDelta())
                .isEqualTo(-5);
        assertThat(sg.getBulkOrderFactor()).isEqualTo(0.75);
        assertThat(narrations()).contains("BULK_ORDER_SHORTFALL");
        assertThat(first.getCharacter().getStatus()).isEqualTo(CharacterStatus.TERMINATED);

        ServiceCase second = request();
        BulkOrder full = bulk.agree(sg, second.getId(), 2);
        bulk.onContractReported(new BridgeEvents.ContractReported(sg.getId(), full.getInstructionId(),
                full.getQuantity(), full.getQuantity(), "MAX_QUANTITY_REACHED"));
        assertThat(full.getStatus()).isEqualTo(BulkOrder.FULFILLED);
        assertThat(trust(second)).containsExactly(TrustReason.BULK_ORDER_FULFILLED);
        assertThat(sg.getBulkOrderFactor()).isEqualTo(1.0);
        // the report repeats in the next files - nothing happens twice
        bulk.onContractReported(new BridgeEvents.ContractReported(sg.getId(), shortOrder.getInstructionId(),
                ordered - 20_000, ordered, "DEADLINE_REACHED"));
        assertThat(instructions()).filteredOn(i -> i.getPayloadJson().contains("CONTRACT_PENALTY")).hasSize(1);
        // forward contracts ignore the report of a bulk order and the other way round
        assertThat(bulk.open(sg)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ calendar

    @Test
    void changedDaysPerPeriodKeepThePendingDeliveryMonth() {
        ForwardContract fc = forwards.conclude(sg, "WHEAT", "MillNorth", 1_000, 4); // month 14
        BulkOrder pending = bulk.agree(sg, request().getId(), 3); // month 13 = days 13..14
        BulkOrder running = bulk.agree(sg, request().getId(), 1); // month 11
        outbox.findByInstructionId(running.getInstructionId()).orElseThrow().setStatus(InstructionStatus.APPLIED);

        // from now on 3 days per period: month 10 starts at day 10, month 13 at day 19
        GameTime.Anchor before = new GameTime.Anchor(10, 10 * DAY, 1, 11);
        GameTime.Anchor after = new GameTime.Anchor(10, 10 * DAY, 3, 11);
        CalendarChangedEvent e = new CalendarChangedEvent(sg.getId(), before, after);
        bulk.onCalendarChanged(e);
        forwards.onCalendarChanged(e);

        assertThat(pending.getDeliveryStartGameTime()).isEqualTo(19 * DAY);
        assertThat(pending.getDeadlineGameTime()).isEqualTo(22 * DAY);
        OutboxInstruction price = outbox.findByInstructionId(pending.getInstructionId()).orElseThrow();
        assertThat(price.getGameTimeEarliest()).isEqualTo(19 * DAY);
        assertThat(json.readTree(price.getPayloadJson()).get("deadlineGameTime").asLong()).isEqualTo(22 * DAY);
        assertThat(json.readTree(price.getPayloadJson()).get("fixedPrice").asLong()).isEqualTo(pending.getFixedPrice());
        OutboxInstruction notice = outbox.findByInstructionId(pending.getNoticeInstructionId()).orElseThrow();
        assertThat(notice.getGameTimeEarliest()).isEqualTo(19 * DAY);
        assertThat(json.readTree(notice.getPayloadJson()).get("expiresAtGameTime").asLong()).isEqualTo(22 * DAY);

        // the mod already has the running month: its end stays (owner decision 2026-10-08)
        assertThat(running.getDeliveryStartGameTime()).isEqualTo(11 * DAY);
        assertThat(running.getDeadlineGameTime()).isEqualTo(12 * DAY);

        // R3-M2 forward contracts get the same rule
        assertThat(fc.getDeliveryStartGameTime()).isEqualTo(22 * DAY);
        assertThat(fc.getDeadlineGameTime()).isEqualTo(25 * DAY);
        assertThat(outbox.findByInstructionId(fc.getInstructionId()).orElseThrow().getGameTimeEarliest())
                .isEqualTo(22 * DAY);
    }
}
