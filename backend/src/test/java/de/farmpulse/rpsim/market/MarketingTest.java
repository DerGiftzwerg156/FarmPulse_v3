package de.farmpulse.rpsim.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.PriceAlarm;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.finance.LiquidityPlanService;
import de.farmpulse.rpsim.neighbor.FarmShopService;
import de.farmpulse.rpsim.neighbor.NeighborService;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
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
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3 R3-M1..M3: price alarms, forward contracts and the farm shop. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class MarketingTest {

    static final long DAY = GameTime.days(1);
    static final String SILOS = """
            "tradeStorage": [{ "fillType": "WHEAT", "amount": 40000, "freeCapacity": 60000 },
                             { "fillType": "POTATO", "amount": 500, "freeCapacity": 9500 }]""";

    @Autowired Fixtures fx;
    @Autowired PriceAlarmService alarms;
    @Autowired ForwardContractService forwards;
    @Autowired MarketEventEngine engine;
    @Autowired FarmShopService shop;
    @Autowired NeighborService neighbors;
    @Autowired LiquidityPlanService plan;
    @Autowired ContractActions actions;
    @Autowired FailedInstructionService failed;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired PublicActionEventRepository publicEvents;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    Character agent;

    @BeforeEach
    void setUp() {
        sg = fx.savegame(); // game time 10 days
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(1);
        sg.setCalPeriod(1);
        agent = fx.character(sg, CharacterRole.LAND_AGENT, CharacterCategory.DYNAMIC, "Ilse Kramer");
        facts(SILOS);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setFarmShop(new RpsimProperties.FarmShop());
    }

    /** Standard farm (WHEAT 215 at MillNorth, 230 at MillSouth, 42,000 l in storage) with a calendar of year 1. */
    private void facts(String extra) {
        String base = TestData.farmFactsWithJournal(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000, 1,
                gameTimePeriod(), "[]");
        fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, extra.isEmpty() ? base : TestData.withFields(base, extra));
    }

    private int gameTimePeriod() {
        return (int) Math.floorMod(sg.getCurrentGameTime() / DAY, 12) + 1;
    }

    private void ingest() {
        alarms.onFacts(new BridgeEvents.FactsIngested(sg.getId(), null, sg.getCurrentGameTime(), false));
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private List<OutboxInstruction> instructions() {
        return outbox.findBySavegameOrderByIdAsc(sg);
    }

    private List<TrustReason> trust() {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(agent).stream().map(TrustEvent::getReason).toList();
    }

    // ------------------------------------------------------------------------------------------ M1

    @Test
    void aPriceAlarmFiresOnceWithAHintInTheGameAndAMail() {
        PriceAlarm any = alarms.create(sg, "WHEAT", null, 220, PriceAlarm.ABOVE);
        PriceAlarm north = alarms.create(sg, "WHEAT", "MillNorth", 200, PriceAlarm.BELOW);
        ingest();
        assertThat(any.getStatus()).isEqualTo(PriceAlarm.FIRED);
        assertThat(any.getFiredPrice()).isEqualTo(230); // the best price of all sell points
        assertThat(any.getFiredSellPoint()).isEqualTo("MillSouth");
        assertThat(north.getStatus()).isEqualTo(PriceAlarm.ACTIVE); // 215 is not below 200
        OutboxInstruction hint = instructions().getLast();
        assertThat(hint.getType()).isEqualTo(InstructionType.NOTIFICATION);
        assertThat(json.readTree(hint.getPayloadJson()).get("expiresAtGameTime").asLong())
                .isEqualTo(sg.getCurrentGameTime() + DAY);
        assertThat(narrations()).containsExactly("PRICE_ALARM");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getFirst().getFactsJson()).contains("\"stockLiters\":42000");

        ingest(); // fires only once
        assertThat(narrations()).hasSize(1);
        alarms.reactivate(sg, any.getId());
        ingest();
        assertThat(narrations()).hasSize(2);
    }

    @Test
    void theNumberOfActiveAlarmsIsCapped() {
        for (int i = 0; i < 10; i++) {
            alarms.create(sg, "WHEAT", null, 1000 + i, PriceAlarm.ABOVE);
        }
        assertThatThrownBy(() -> alarms.create(sg, "WHEAT", null, 2000, PriceAlarm.ABOVE))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("10");
        assertThatThrownBy(() -> alarms.create(sg, "WHEAT", null, 2000, "SIDEWAYS"))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ------------------------------------------------------------------------------------------ M2

    @Test
    void aForwardContractFixesThePriceForTheDeliveryMonth() {
        ForwardContractService.Quote q = forwards.quote(sg, "WHEAT", "MillNorth", 10_000, 3);
        assertThat(q.fixedPrice()).isEqualTo(Math.round(215 * 0.94)); // 2 % discount per month of lead
        assertThat(q.deliveryStartGameTime()).isEqualTo(13 * DAY); // month index 10 + 3
        assertThat(q.deadlineGameTime()).isEqualTo(14 * DAY);
        assertThat(q.expectedIncome()).isEqualTo(Math.round(10 * q.fixedPrice()));

        ForwardContract fc = forwards.conclude(sg, "WHEAT", "MillNorth", 10_000, 3);
        OutboxInstruction ins = instructions().getLast();
        assertThat(ins.getType()).isEqualTo(InstructionType.PRICE_EVENT);
        assertThat(ins.getGameTimeEarliest()).isEqualTo(13 * DAY);
        var p = json.readTree(ins.getPayloadJson());
        assertThat(p.get("priceMode").asString()).isEqualTo("FIXED");
        assertThat(p.get("fixedPrice").asLong()).isEqualTo(fc.getFixedPrice());
        assertThat(p.get("maxQuantity").asLong()).isEqualTo(10_000);
        assertThat(p.get("deadlineGameTime").asLong()).isEqualTo(14 * DAY);
        assertThat(fc.getInstructionId()).isEqualTo(ins.getInstructionId());

        // one fixed price per sell point and fill type; the event engine leaves the pair alone
        assertThatThrownBy(() -> forwards.conclude(sg, "WHEAT", "MillNorth", 1_000, 5))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Festpreis");
        assertThat(forwards.openPairs(sg)).containsExactly("MillNorth|WHEAT");
        assertThatThrownBy(() -> forwards.quote(sg, "WHEAT", "MillNorth", 1_500, 3)).hasMessageContaining("Schritten");
        assertThatThrownBy(() -> forwards.quote(sg, "WHEAT", "MillNorth", 1_000, 13)).hasMessageContaining("Monate");
        assertThatThrownBy(() -> forwards.quote(sg, "BARLEY", "MillNorth", 1_000, 3)).hasMessageContaining("keinen Preis");
    }

    @Test
    void aShortfallCostsThePenaltyAndAFullDeliveryGainsTrust() {
        ForwardContract short1 = forwards.conclude(sg, "WHEAT", "MillNorth", 10_000, 1);
        forwards.onContractReported(new BridgeEvents.ContractReported(sg.getId(), short1.getInstructionId(), 6_000,
                10_000, "DEADLINE_REACHED"));
        assertThat(short1.getStatus()).isEqualTo(ForwardContract.SHORTFALL);
        long penalty = Math.round(4 * short1.getFixedPrice() * 0.25);
        assertThat(short1.getPenalty()).isEqualTo(penalty);
        assertThat(instructions().getLast().getPayloadJson()).contains("CONTRACT_PENALTY", "-" + penalty);
        assertThat(trust()).contains(TrustReason.FORWARD_CONTRACT_SHORTFALL);
        assertThat(narrations()).contains("FORWARD_CONTRACT_SHORTFALL");

        ForwardContract full = forwards.conclude(sg, "WHEAT", "MillSouth", 5_000, 1);
        forwards.onContractReported(new BridgeEvents.ContractReported(sg.getId(), full.getInstructionId(), 5_000, 5_000,
                "MAX_QUANTITY_REACHED"));
        assertThat(full.getStatus()).isEqualTo(ForwardContract.FULFILLED);
        assertThat(trust()).contains(TrustReason.FORWARD_CONTRACT_FULFILLED);
        // the report repeats in the next files - nothing happens twice
        forwards.onContractReported(new BridgeEvents.ContractReported(sg.getId(), short1.getInstructionId(), 6_000,
                10_000, "DEADLINE_REACHED"));
        assertThat(instructions()).filteredOn(i -> i.getPayloadJson().contains("CONTRACT_PENALTY")).hasSize(1);
    }

    @Test
    void theLiquidityPlanShowsTheExpectedIncomeInTheDeliveryMonth() {
        ForwardContract fc = forwards.conclude(sg, "WHEAT", "MillNorth", 10_000, 2);
        LiquidityPlanService.MonthPlan m = plan.plan(sg).months().get(1);
        assertThat(m.postings()).anySatisfy(x -> {
            assertThat(x.kind()).isEqualTo("FORWARD_CONTRACT");
            assertThat(x.amount()).isEqualTo(Math.round(10 * fc.getFixedPrice()));
            assertThat(x.estimate()).isTrue();
        });
        assertThat(plan.plan(sg).months().get(0).postings()).noneMatch(x -> x.kind().equals("FORWARD_CONTRACT"));
    }

    // ------------------------------------------------------------------------------------------ M3

    private Character villager() {
        return fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Grete Lammers");
    }

    @Test
    void aVillagerOrdersFromTheSiloAndTheDeliveryBooksTheGoodsOut() {
        villager();
        props.getFormulas().getFarmShop().setFillTypes(List.of("WHEAT", "POTATO", "OAT"));
        ServiceCase order = shop.spawnOrder(sg, neighbors.latest(sg).orElseThrow()).orElseThrow();
        // WHEAT only: POTATO has no price on the map, OAT no silo
        assertThat(order.getKind()).isEqualTo(CaseKind.FARM_SHOP_ORDER);
        assertThat(order.getReference()).isEqualTo("WHEAT");
        assertThat(order.getQuantity()).isBetween(200, 2000);
        assertThat(order.getQuantity() % 100).isZero();
        assertThat(order.getCostAmount()).isEqualTo(Math.round(230 * 1.3)); // best market price x 1.3
        assertThat(narrations()).contains("FARM_SHOP_ORDER");

        actions.acceptCase(sg, order.getId());
        assertThat(order.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        List<OutboxInstruction> deal = instructions().stream()
                .filter(i -> order.getId().equals(i.getRelatedEntityId())).toList();
        OutboxInstruction transfer = deal.stream().filter(i -> i.getType() == InstructionType.STORAGE_TRANSFER).findFirst().orElseThrow();
        assertThat(json.readTree(transfer.getPayloadJson()).get("direction").asString()).isEqualTo("OUT");
        assertThat(deal).anyMatch(i -> i.getPayloadJson().contains("GOODS_SALE"));

        transfer.setStatus(InstructionStatus.APPLIED);
        shop.onAck(new BridgeEvents.InstructionAcked(sg.getId(), transfer.getInstructionId(), "APPLIED",
                transfer.getRelatedEntityType(), transfer.getRelatedEntityId(), Map.of()));
        assertThat(order.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(publicEvents.findBySavegameOrderByGameTimeAsc(sg)).extracting(e -> e.getType())
                .containsExactly(PublicActionType.FARM_SHOP);
    }

    @Test
    void refusalsMakeOrdersRarerAndDeliveriesRaiseTheChanceAgain() {
        villager();
        ServiceCase first = shop.spawnOrder(sg, neighbors.latest(sg).orElseThrow()).orElseThrow();
        actions.declineCase(sg, first.getId());
        assertThat(sg.getFarmShopFactor()).isEqualTo(0.75);
        ServiceCase second = shop.spawnOrder(sg, neighbors.latest(sg).orElseThrow()).orElseThrow();
        sg.setCurrentGameTime(second.getDeadlineGameTime() + 1);
        shop.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
        assertThat(second.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sg.getFarmShopFactor()).isEqualTo(0.5625);
        for (int i = 0; i < 12; i++) {
            actions.declineCase(sg, shop.spawnOrder(sg, neighbors.latest(sg).orElseThrow()).orElseThrow().getId());
        }
        assertThat(sg.getFarmShopFactor()).isEqualTo(0.1); // never below min-factor
    }

    @Test
    void anOrderFailsWhenTheSiloIsEmptyAtBookingTime() {
        villager();
        ServiceCase order = shop.spawnOrder(sg, neighbors.latest(sg).orElseThrow()).orElseThrow();
        actions.acceptCase(sg, order.getId());
        OutboxInstruction transfer = instructions().stream().filter(i -> order.getId().equals(i.getRelatedEntityId())
                && i.getType() == InstructionType.STORAGE_TRANSFER).findFirst().orElseThrow();
        assertThat(shop.onInstructionFailed(order.getId(), "INSUFFICIENT_STOCK")).isTrue();
        assertThat(order.getResolution()).isEqualTo("NO_STOCK");
        assertThat(transfer.getInstructionId()).isNotBlank();
    }
}
