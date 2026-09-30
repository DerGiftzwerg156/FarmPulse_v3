package de.farmpulse.rpsim.neighbor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.repository.FarmlandOwnershipRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
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

/** Roadmap V3 R3-H1..H5: neighbour fields and stock, trade from and into the own silos, real contracts. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class NeighborTradeTest {

    static final String SILOS = """
            "tradeStorage": [{ "fillType": "STRAW", "amount": 0, "freeCapacity": 25000 },
                             { "fillType": "WHEAT", "amount": 40000, "freeCapacity": 60000 },
                             { "fillType": "SILAGE", "amount": 20000, "freeCapacity": 0 }]""";

    @Autowired Fixtures fx;
    @Autowired NeighborService neighbors;
    @Autowired NpcFieldService fields;
    @Autowired NeighborTradeService trade;
    @Autowired NeighborMissionService missions;
    @Autowired ContractActions actions;
    @Autowired FailedInstructionService failed;
    @Autowired FarmlandOwnershipRepository ownership;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired OutboxService outboxService;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired DiaryEntryRepository diary;
    @Autowired PublicActionEventRepository publicEvents;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;
    @Autowired de.farmpulse.rpsim.prompt.PromptService prompts;
    @Autowired de.farmpulse.rpsim.repository.GamePromptRepository promptRepo;

    Savegame sg;
    Character otto;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        otto = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Otto Wendler");
        otto.setNeighborRole("DAIRY");
        own(3, otto, 6.5);
        own(4, otto, 3.0);
        facts(250_000, SILOS);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setNeighborTrade(new RpsimProperties.NeighborTrade());
        props.getFormulas().setNeighborMissions(new RpsimProperties.NeighborMissions());
    }

    private void own(int farmlandId, Character c, double hectares) {
        FarmlandOwnership o = new FarmlandOwnership();
        o.setSavegame(sg);
        o.setFarmlandId(farmlandId);
        o.setOwnerType(OwnerType.CHARACTER);
        o.setOwnerCharacter(c);
        o.setHectares(hectares);
        o.setReferencePrice(Math.round(hectares * 12000));
        ownership.save(o);
    }

    private FarmFacts facts(long balance, String extra) {
        String base = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), balance);
        fx.snapshot(sg, sg.getCurrentGameTime(), balance, extra.isEmpty() ? base : TestData.withFields(base, extra));
        return neighbors.latest(sg).orElseThrow();
    }

    private static BridgeDtos.Field field(int farmlandId, String fruit, int growth, Boolean cut, int plowLevel,
                                          int stoneLevel, double hectares) {
        return new BridgeDtos.Field(farmlandId, String.valueOf(farmlandId), hectares, fruit, growth,
                fruit == null ? null : 8, fruit == null ? null : 8, 0, stoneLevel, 0, 1, plowLevel, "SOWN", false, cut,
                fruit, fruit == null ? null : 0.95);
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private List<TrustReason> trust(Character c) {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(c).stream().map(TrustEvent::getReason).toList();
    }

    private List<OutboxInstruction> instructions(ServiceCase sc) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream()
                .filter(o -> sc.getId().equals(o.getRelatedEntityId())).toList();
    }

    /** Simulates the ack path of BridgeSyncService for an instruction. */
    private void ack(OutboxInstruction ins, String status, String message, Map<String, Object> result) {
        ins.setStatus("APPLIED".equals(status) ? InstructionStatus.APPLIED : InstructionStatus.FAILED);
        ins.setAckMessage(message);
        var e = new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), status, ins.getRelatedEntityType(),
                ins.getRelatedEntityId(), result);
        trade.onAck(e);
        missions.onAck(e);
        failed.onAck(e);
    }

    private OutboxInstruction ofType(ServiceCase sc, InstructionType type) {
        return instructions(sc).stream().filter(o -> o.getType() == type).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------ H1 / H2

    @Test
    void aHarvestOfANeighbourFieldFillsHisStockWithGrainAndStraw() {
        long t = sg.getCurrentGameTime();
        fields.update(sg, List.of(field(3, "WHEAT", 8, false, 1, 0, 6.5), field(9, "BARLEY", 8, false, 1, 0, 2)), t, 1);
        var harvests = fields.update(sg, List.of(field(3, "WHEAT", 9, true, 1, 0, 6.5),
                field(9, "BARLEY", 9, true, 1, 0, 2)), t + GameTime.hours(2), 1);
        // farmland 9 has no neighbour as owner: nothing is credited
        assertThat(harvests).singleElement().satisfies(h -> assertThat(h.neighbor().getId()).isEqualTo(otto.getId()));
        assertThat(neighbors.stockOf(otto, "WHEAT")).isCloseTo(6.5 * 10_000 * 0.95 * 0.3, org.assertj.core.data.Offset.offset(0.01));
        assertThat(neighbors.stockOf(otto, "STRAW")).isCloseTo(6.5 * 10_000 * 0.95 * 0.3 * 0.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(fields.crops(sg, 3)).singleElement().satisfies(c -> assertThat(c.isHarvested()).isTrue());

        // a sample of an older save (reload without saving) never credits a harvest twice
        fields.update(sg, List.of(field(3, "WHEAT", 8, false, 1, 0, 6.5)), t + GameTime.hours(3), 1);
        assertThat(fields.update(sg, List.of(field(3, "WHEAT", 9, true, 1, 0, 6.5)), t + GameTime.hours(1), 1)).isEmpty();

        // the stock sinks every game month
        double before = neighbors.stockOf(otto, "WHEAT");
        neighbors.onMonth(new GameMonthPassedEvent(sg.getId(), 1, t));
        assertThat(neighbors.stockOf(otto, "WHEAT")).isCloseTo(before * 0.8, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void pricesComeFromTheBestSellPointOrTheReferenceAndTrustIsCapped() {
        FarmFacts f = neighbors.latest(sg).orElseThrow();
        assertThat(neighbors.basePrice(f, "WHEAT").getAsDouble()).isEqualTo(230); // best of 215 / 230
        assertThat(neighbors.basePrice(f, "STRAW").getAsDouble()).isEqualTo(120); // no sell point: reference price
        assertThat(neighbors.basePrice(f, "UNOBTAINIUM")).isEmpty();
        assertThat(neighbors.sellUnitPrice(f, otto, "WHEAT").getAsDouble()).isCloseTo(241.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(neighbors.buyUnitPrice(f, otto, "WHEAT").getAsDouble()).isCloseTo(218.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(neighbors.trustAdjustment(10)).isEqualTo(0.05); // 10 / 20 capped at 5 %
        assertThat(neighbors.trustAdjustment(-40)).isEqualTo(-0.05);
        assertThat(neighbors.trustAdjustment(0.5)).isEqualTo(0.025);
    }

    @Test
    void neighboursGetARoleFromTheConfigWhenFirstNeeded() {
        Character heinrich = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Heinrich Brandt");
        assertThat(heinrich.getNeighborRole()).isNull();
        assertThat(neighbors.neighbors(sg)).extracting(Character::getName).contains("Heinrich Brandt", "Otto Wendler");
        assertThat(heinrich.getNeighborRole()).isIn("DAIRY", "ARABLE", "MIXED");
        assertThat(neighbors.needs(otto)).containsExactly("STRAW", "SILAGE", "DRYGRASS_WINDROW");
    }

    // ------------------------------------------------------------------------------------------ H3

    @Test
    void thePlayerBuysGoodsFromANeighbourAndTheyGoIntoHisSilo() {
        neighbors.addStock(sg, otto, "WHEAT", 18_000);
        ServiceCase sc = trade.requestGoods(sg, otto.getId(), "WHEAT", 5_000);
        assertThat(sc.getKind()).isEqualTo(CaseKind.GOODS_OFFER);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
        assertThat(sc.getOfferAmount()).isEqualTo(Math.round(241.5 * 5)); // 5,000 l at 241.50 € per 1000 l
        assertThat(sc.getTitle()).isEqualTo(NeighborTradeService.PLAYER_REQUEST);
        assertThat(narrations()).contains("GOODS_OFFER");

        actions.acceptCase(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        assertThat(neighbors.stockOf(otto, "WHEAT")).isEqualTo(13_000); // reserved
        OutboxInstruction transfer = ofType(sc, InstructionType.STORAGE_TRANSFER);
        OutboxInstruction money = ofType(sc, InstructionType.MONEY_TRANSACTION);
        assertThat(transfer.getBatchId()).isNotNull().isEqualTo(money.getBatchId());
        assertThat(json.readTree(transfer.getPayloadJson()).get("direction").asString()).isEqualTo("IN");
        assertThat(json.readTree(transfer.getPayloadJson()).get("amount").asLong()).isEqualTo(5_000);
        assertThat(json.readTree(money.getPayloadJson()).get("amount").asLong()).isEqualTo(-sc.getOfferAmount());
        assertThat(json.readTree(money.getPayloadJson()).get("reason").asString()).isEqualTo("GOODS_PURCHASE");

        ack(transfer, "APPLIED", null, Map.of());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(trust(otto)).contains(TrustReason.NEIGHBOR_TRADE);
        assertThat(diary.findBySavegameOrderByGameTimeAscIdAsc(sg)).anySatisfy(d ->
                assertThat(d.getText()).contains("5000 l Weizen von Otto Wendler gekauft"));
        assertThat(narrations()).contains("GOODS_TRADE_DONE");
    }

    @Test
    void theRequestIsCheckedAgainstStockRoomAndMoney() {
        neighbors.addStock(sg, otto, "WHEAT", 3_000);
        neighbors.addStock(sg, otto, "SILAGE", 10_000);
        assertThatThrownBy(() -> trade.requestGoods(sg, otto.getId(), "WHEAT", 5_000))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nicht so viel");
        assertThatThrownBy(() -> trade.requestGoods(sg, otto.getId(), "SILAGE", 2_000))
                .hasMessageContaining("nicht genug Platz"); // the silage silo is full
        facts(100, SILOS);
        assertThatThrownBy(() -> trade.requestGoods(sg, otto.getId(), "WHEAT", 2_000))
                .hasMessageContaining("Kontostand");
        facts(250_000, ""); // older mod without tradeStorage
        assertThatThrownBy(() -> trade.requestGoods(sg, otto.getId(), "WHEAT", 2_000))
                .hasMessageContaining("aktualisieren");
    }

    @Test
    void aRefusedPurchaseGivesTheStockBack() {
        neighbors.addStock(sg, otto, "WHEAT", 18_000);
        ServiceCase sc = trade.requestGoods(sg, otto.getId(), "WHEAT", 5_000);
        actions.acceptCase(sg, sc.getId());
        ack(ofType(sc, InstructionType.STORAGE_TRANSFER), "FAILED", "NO_CAPACITY", Map.of());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sc.getResolution()).isEqualTo("FAILED");
        assertThat(neighbors.stockOf(otto, "WHEAT")).isEqualTo(18_000);
    }

    @Test
    void neighboursOfferOnlyGoodsThePlayerHasRoomFor() {
        neighbors.addStock(sg, otto, "SILAGE", 30_000); // silage silo full
        assertThat(trade.spawnOffer(sg, neighbors.latest(sg).orElseThrow())).isEmpty();
        neighbors.addStock(sg, otto, "STRAW", 30_000);
        ServiceCase sc = trade.spawnOffer(sg, neighbors.latest(sg).orElseThrow()).orElseThrow();
        assertThat(sc.getReference()).isEqualTo("STRAW");
        assertThat(sc.getQuantity()).isBetween(2_000, 10_000); // max-share of 30,000 l capped at amount-max
        assertThat(sc.getQuantity() % 500).isZero();
        assertThat(sc.getCostAmount()).isEqualTo(126); // reference 120 € x 1.05
        assertThat(sc.getTitle()).isEqualTo(NeighborTradeService.NEIGHBOR_INITIATIVE);
    }

    @Test
    void decliningCostsLittleTrustIgnoringMore() {
        neighbors.addStock(sg, otto, "WHEAT", 18_000);
        ServiceCase declined = trade.requestGoods(sg, otto.getId(), "WHEAT", 2_000);
        actions.declineCase(sg, declined.getId());
        assertThat(declined.getStatus()).isEqualTo(CaseStatus.DECLINED);
        ServiceCase ignored = trade.requestGoods(sg, otto.getId(), "WHEAT", 2_000);
        sg.setCurrentGameTime(sg.getCurrentGameTime() + GameTime.days(6));
        trade.onDay(new GameDayPassedEvent(sg.getId(), 16, sg.getCurrentGameTime()));
        assertThat(ignored.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(otto)).extracting(TrustEvent::getReason, TrustEvent::getDelta)
                .contains(org.assertj.core.groups.Tuple.tuple(TrustReason.NEIGHBOR_TRADE_DECLINED, -1.0),
                        org.assertj.core.groups.Tuple.tuple(TrustReason.NEIGHBOR_TRADE_IGNORED, -2.0));
    }

    // ------------------------------------------------------------------------------------------ H4

    @Test
    void aNeighbourAsksForGoodsOfHisNeedsAndTheSaleRaisesTheReputationCapped() {
        FarmFacts f = neighbors.latest(sg).orElseThrow();
        for (int i = 0; i < 4; i++) {
            ServiceCase sc = trade.spawnRequest(sg, f).orElseThrow();
            assertThat(sc.getKind()).isEqualTo(CaseKind.GOODS_REQUEST);
            assertThat(sc.getReference()).isEqualTo("SILAGE"); // DAIRY needs silage, the player has 20,000 l
            assertThat(sc.getQuantity()).isBetween(2_000, 10_000);
            actions.acceptCase(sg, sc.getId());
            OutboxInstruction transfer = ofType(sc, InstructionType.STORAGE_TRANSFER);
            assertThat(json.readTree(transfer.getPayloadJson()).get("direction").asString()).isEqualTo("OUT");
            OutboxInstruction money = ofType(sc, InstructionType.MONEY_TRANSACTION);
            assertThat(json.readTree(money.getPayloadJson()).get("reason").asString()).isEqualTo("GOODS_SALE");
            assertThat(json.readTree(money.getPayloadJson()).get("amount").asLong()).isEqualTo(sc.getOfferAmount());
            ack(transfer, "APPLIED", null, Map.of());
            assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        }
        // at most 3 reputation events per FS25 year
        assertThat(publicEvents.findBySavegameOrderByGameTimeAsc(sg)).filteredOn(e -> e.getType() == PublicActionType.NEIGHBOR_HELP)
                .hasSize(3);
        // an ARABLE neighbour needs nothing the player has
        otto.setNeighborRole("ARABLE");
        assertThat(trade.spawnRequest(sg, f)).isEmpty();
    }

    @Test
    void tooLittleInTheSiloDisappointsTheNeighbour() {
        ServiceCase sc = trade.spawnRequest(sg, neighbors.latest(sg).orElseThrow()).orElseThrow();
        actions.acceptCase(sg, sc.getId());
        ack(ofType(sc, InstructionType.STORAGE_TRANSFER), "FAILED", "INSUFFICIENT_STOCK", Map.of());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sc.getResolution()).isEqualTo("NO_STOCK");
        assertThat(trust(otto)).contains(TrustReason.NEIGHBOR_DISAPPOINTED);
        assertThat(narrations()).contains("GOODS_REQUEST_FAILED");
    }

    // ------------------------------------------------------------------------------------------ H5

    private void neighbourFields() {
        long t = sg.getCurrentGameTime();
        fields.update(sg, List.of(field(3, "WHEAT", 8, false, 1, 0, 6.5), field(4, null, 0, null, 1, 3, 3.0)), t, 1);
        fields.update(sg, List.of(field(3, "WHEAT", 9, true, 0, 0, 6.5), field(4, null, 0, null, 1, 3, 3.0)),
                t + GameTime.hours(1), 1);
    }

    private FarmFacts withMissions(String missions, boolean limit) {
        return facts(250_000, SILOS + ", \"npcFields\": [], \"missionLimitReached\": " + limit + ", \"missions\": " + missions);
    }

    @Test
    void neighboursAskForPlowingAndStonePickingOnTheirFields() {
        neighbourFields();
        FarmFacts f = withMissions("[]", false);
        assertThat(missions.work(sg, f, null)).extracting(w -> w.field().getFarmlandId() + ":" + w.missionType())
                .containsExactlyInAnyOrder("3:PLOW", "4:STONE_PICK");
        withMissions("[]", true);
        assertThatThrownBy(() -> missions.askForWork(sg, otto.getId())).hasMessageContaining("Höchstzahl");
        withMissions("[]", false);
        ServiceCase sc = missions.askForWork(sg, otto.getId());
        assertThat(sc.getKind()).isEqualTo(CaseKind.NEIGHBOR_MISSION);
        assertThat(narrations()).contains("NEIGHBOR_MISSION_REQUEST");
        // a field with an open request is not offered twice
        assertThat(missions.work(sg, neighbors.latest(sg).orElseThrow(), null))
                .noneMatch(w -> w.field().getFarmlandId() == sc.getFarmlandId());
    }

    @Test
    void anAcceptedContractIsCreatedInTheGameAndItsEndIsEvaluated() {
        neighbourFields();
        withMissions("[]", false);
        ServiceCase sc = missions.askForWork(sg, otto.getId());
        actions.acceptCase(sg, sc.getId());
        OutboxInstruction create = ofType(sc, InstructionType.MISSION_CREATE);
        assertThat(json.readTree(create.getPayloadJson()).get("missionType").asString()).isEqualTo(sc.getReference());
        assertThat(json.readTree(create.getPayloadJson()).get("farmlandId").asInt()).isEqualTo(sc.getFarmlandId());
        ack(create, "APPLIED", null, Map.of("missionId", "m_77"));
        assertThat(sc.getExternalId()).isEqualTo("m_77");

        withMissions("[{ \"uniqueId\": \"m_77\", \"status\": \"AVAILABLE\" }]", false);
        missions.onFacts(new BridgeEvents.FactsIngested(sg.getId(), 1L, sg.getCurrentGameTime(), false));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);

        withMissions("[{ \"uniqueId\": \"m_77\", \"status\": \"FINISHED\", \"success\": true }]", false);
        missions.onFacts(new BridgeEvents.FactsIngested(sg.getId(), 1L, sg.getCurrentGameTime(), false));
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(trust(otto)).contains(TrustReason.MISSION_COMPLETED);
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).anySatisfy(o -> {
            assertThat(o.getType()).isEqualTo(InstructionType.MONEY_TRANSACTION);
            assertThat(json.readTree(o.getPayloadJson()).get("amount").asLong()).isEqualTo(250);
            assertThat(json.readTree(o.getPayloadJson()).get("reason").asString()).isEqualTo("OTHER");
        });
        assertThat(narrations()).contains("NEIGHBOR_MISSION_THANKS");
    }

    @Test
    void aContractThatExpiresDisappointsALostOneIsOfferedAgain() {
        neighbourFields();
        withMissions("[]", false);
        ServiceCase expired = missions.askForWork(sg, otto.getId());
        actions.acceptCase(sg, expired.getId());
        ack(ofType(expired, InstructionType.MISSION_CREATE), "APPLIED", null, Map.of("missionId", "m_1"));
        missions.onFacts(new BridgeEvents.FactsIngested(sg.getId(), 1L, sg.getCurrentGameTime(), false));
        assertThat(expired.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(expired.getResolution()).isEqualTo("FAILED");
        assertThat(trust(otto)).contains(TrustReason.MISSION_FAILED);
        assertThat(narrations()).contains("NEIGHBOR_MISSION_DISAPPOINTED");

        ServiceCase lost = missions.askForWork(sg, otto.getId());
        actions.acceptCase(sg, lost.getId());
        ack(ofType(lost, InstructionType.MISSION_CREATE), "APPLIED", null, Map.of("missionId", "m_2"));
        missions.onRewound(new BridgeEvents.Rewound(sg.getId(), sg.getCurrentGameTime(), sg.getCurrentGameTime() - 1));
        missions.onFacts(new BridgeEvents.FactsIngested(sg.getId(), 1L, sg.getCurrentGameTime(), false));
        assertThat(lost.getResolution()).isEqualTo("LOST");
        assertThat(cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.NEIGHBOR_MISSION)))
                .anySatisfy(c -> {
                    assertThat(c.getStatus()).isEqualTo(CaseStatus.AWAITING_PLAYER);
                    assertThat(c.getFarmlandId()).isEqualTo(lost.getFarmlandId());
                });
    }

    @Test
    void theGameRefusingTheContractClosesTheRequest() {
        neighbourFields();
        withMissions("[]", false);
        ServiceCase sc = missions.askForWork(sg, otto.getId());
        actions.acceptCase(sg, sc.getId());
        ack(ofType(sc, InstructionType.MISSION_CREATE), "FAILED", "NOT_AVAILABLE", Map.of());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sc.getResolution()).isEqualTo("NOT_AVAILABLE");
    }
    // ------------------------------------------------------------------------------------------ questions in the game

    @Test
    void offersRequestsAndContractsCanBeAskedInTheGameWhenSwitchedOn() {
        ServiceCase request = trade.spawnRequest(sg, neighbors.latest(sg).orElseThrow()).orElseThrow();
        prompts.sync(sg);
        assertThat(promptRepo.findBySavegameOrderByIdDesc(sg)).isEmpty(); // default: off (owner decision)
        prompts.setEnabledKinds(sg, EnumSet.of(de.farmpulse.rpsim.domain.PromptKind.NEIGHBOR_TRADE));
        prompts.sync(sg);
        var p = promptRepo.findBySavegameOrderByIdDesc(sg).get(0);
        assertThat(p.getKind()).isEqualTo(de.farmpulse.rpsim.domain.PromptKind.NEIGHBOR_TRADE);
        assertThat(p.getText()).contains("Silage").contains("Otto Wendler");
        prompts.onResponses(new BridgeEvents.PlayerResponsesRead(sg.getId(), List.of(new BridgeDtos.PlayerAnswer(
                "rsp_" + p.getPromptId(), p.getPromptId(), "NO", sg.getCurrentGameTime()))));
        prompts.processAnswers();
        assertThat(request.getStatus()).isEqualTo(CaseStatus.DECLINED); // the same method as the browser button
    }
}

