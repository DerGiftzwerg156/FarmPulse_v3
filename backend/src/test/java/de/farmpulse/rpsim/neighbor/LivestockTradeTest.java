package de.farmpulse.rpsim.neighbor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractActions;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
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

/** Roadmap V3.1 R31-A3: livestock trade with the neighbours (owner decisions 2026-10-02). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class LivestockTradeTest {

    /** Own cow stable hus_00003 (24 cows, 96,000 € = 4,000 € per cow): 18 Holstein, 6 Angus, 10 free places. */
    static final String STABLE = """
            "husbandries": [{ "husbandryUniqueId": "hus_00003", "health": 85, "food": 0.7, "conditions": [],
                              "subTypes": [{ "name": "COW_ANGUS", "count": 6 }, { "name": "COW_HOLSTEIN", "count": 18 }],
                              "supportedSubTypes": ["COW_ANGUS", "COW_HOLSTEIN", "COW_SWISS_BROWN"], "freeSlots": 10 }]""";

    @Autowired Fixtures fx;
    @Autowired LivestockTradeService livestock;
    @Autowired NeighborService neighbors;
    @Autowired ContractActions actions;
    @Autowired FailedInstructionService failed;
    @Autowired ServiceCaseRepository cases;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired DiaryEntryRepository diary;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    Character otto;
    Character greta;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        otto = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Otto Wendler");
        otto.setNeighborRole("DAIRY");
        greta = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Greta Lindner");
        greta.setNeighborRole("ARABLE");
        facts(250_000, STABLE);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setLivestockTrade(new RpsimProperties.LivestockTrade());
    }

    private FarmFacts facts(long balance, String extra) {
        String base = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), balance);
        fx.snapshot(sg, sg.getCurrentGameTime(), balance, extra.isEmpty() ? base : TestData.withFields(base, extra));
        return neighbors.latest(sg).orElseThrow();
    }

    private List<OutboxInstruction> instructions(ServiceCase sc) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> sc.getId().equals(o.getRelatedEntityId())).toList();
    }

    private OutboxInstruction ofType(ServiceCase sc, InstructionType type) {
        return instructions(sc).stream().filter(o -> o.getType() == type).findFirst().orElseThrow();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(NarrationJob::getEventType).toList();
    }

    private List<TrustReason> trust(Character c) {
        return trustEvents.findByCharacterOrderByGameTimeAscIdAsc(c).stream().map(TrustEvent::getReason).toList();
    }

    private void ack(OutboxInstruction ins, String status, String message) {
        ins.setStatus("APPLIED".equals(status) ? InstructionStatus.APPLIED : InstructionStatus.FAILED);
        ins.setAckMessage(message);
        var e = new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), status, ins.getRelatedEntityType(),
                ins.getRelatedEntityId(), Map.of());
        livestock.onAck(e);
        failed.onAck(e);
    }

    @Test
    void stablesComeFromTheExportAndTheNeighboursKeepTheAnimalsOfTheirRole() {
        var stables = livestock.stables(neighbors.latest(sg).orElseThrow());
        assertThat(stables).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo("COW");
            assertThat(s.freeSlots()).isEqualTo(10);
            assertThat(s.supportedSubTypes()).contains("COW_SWISS_BROWN");
        });
        assertThat(livestock.animalTypes(otto)).containsExactly("COW");
        assertThat(livestock.animalTypes(greta)).isEmpty(); // arable farm: no animals
        int stock = livestock.stockOf(sg, otto, "COW");
        assertThat(stock).isBetween(20, 60);
        assertThat(livestock.stockOf(sg, otto, "COW")).isEqualTo(stock); // rolled once
        assertThat(livestock.stockOf(sg, otto, "PIG")).isZero();
    }

    @Test
    void thePriceFollowsTheGameValuePerAnimalAndTheTrust() {
        FarmFacts f = neighbors.latest(sg).orElseThrow();
        assertThat(livestock.valuePerAnimal(f, "COW").getAsDouble()).isEqualTo(4_000);
        assertThat(livestock.sellUnitPrice(f, otto, "COW").getAsDouble()).isCloseTo(4_200, org.assertj.core.data.Offset.offset(0.01));
        assertThat(livestock.buyUnitPrice(f, otto, "COW").getAsDouble()).isCloseTo(3_800, org.assertj.core.data.Offset.offset(0.01));
        assertThat(livestock.valuePerAnimal(f, "PIG")).isEmpty();
    }

    @Test
    void thePlayerBuysCalvesThatGoIntoHisStable() {
        ServiceCase sc = livestock.requestAnimals(sg, otto.getId(), "hus_00003", "COW_SWISS_BROWN", 4);
        assertThat(sc.getKind()).isEqualTo(CaseKind.ANIMAL_OFFER);
        assertThat(sc.getOfferAmount()).isEqualTo(16_800); // 4 x 4,200 €
        assertThat(narrations()).contains("ANIMAL_OFFER");
        int stock = livestock.stockOf(sg, otto, "COW");

        actions.acceptCase(sg, sc.getId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        assertThat(livestock.stockOf(sg, otto, "COW")).isEqualTo(stock - 4); // reserved
        OutboxInstruction transfer = ofType(sc, InstructionType.ANIMAL_TRANSFER);
        OutboxInstruction money = ofType(sc, InstructionType.MONEY_TRANSACTION);
        assertThat(transfer.getBatchId()).isNotNull().isEqualTo(money.getBatchId());
        var p = json.readTree(transfer.getPayloadJson());
        assertThat(p.get("direction").asString()).isEqualTo("IN");
        assertThat(p.get("husbandryUniqueId").asString()).isEqualTo("hus_00003");
        assertThat(p.get("subType").asString()).isEqualTo("COW_SWISS_BROWN");
        assertThat(p.get("count").asInt()).isEqualTo(4);
        assertThat(p.get("age").asInt()).isEqualTo(12);
        assertThat(json.readTree(money.getPayloadJson()).get("reason").asString()).isEqualTo("LIVESTOCK_PURCHASE");
        assertThat(json.readTree(money.getPayloadJson()).get("amount").asLong()).isEqualTo(-16_800);

        ack(transfer, "APPLIED", null);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(trust(otto)).contains(TrustReason.NEIGHBOR_TRADE);
        assertThat(diary.findBySavegameOrderByGameTimeAscIdAsc(sg)).anySatisfy(d ->
                assertThat(d.getText()).contains("4 COW_SWISS_BROWN von Otto Wendler gekauft"));
        assertThat(narrations()).contains("ANIMAL_TRADE_DONE", "ANIMAL_TRADE_GOSSIP"); // the village talks about it
    }

    @Test
    void thePlayerSellsAnimalsHeHasAndTheNeighbourGetsThem() {
        int stock = livestock.stockOf(sg, otto, "COW");
        ServiceCase sc = livestock.offerAnimals(sg, otto.getId(), "hus_00003", "COW_ANGUS", 5);
        assertThat(sc.getKind()).isEqualTo(CaseKind.ANIMAL_REQUEST);
        assertThat(sc.getOfferAmount()).isEqualTo(19_000); // 5 x 3,800 €
        actions.acceptCase(sg, sc.getId());
        OutboxInstruction transfer = ofType(sc, InstructionType.ANIMAL_TRANSFER);
        assertThat(json.readTree(transfer.getPayloadJson()).get("direction").asString()).isEqualTo("OUT");
        assertThat(json.readTree(transfer.getPayloadJson()).has("age")).isFalse();
        assertThat(json.readTree(ofType(sc, InstructionType.MONEY_TRANSACTION).getPayloadJson()).get("reason").asString())
                .isEqualTo("LIVESTOCK_SALE");
        // the 5 Angus are reserved: only 1 is left for another sale
        assertThatThrownBy(() -> livestock.offerAnimals(sg, otto.getId(), "hus_00003", "COW_ANGUS", 2))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nicht so viele");
        ack(transfer, "APPLIED", null);
        assertThat(livestock.stockOf(sg, otto, "COW")).isEqualTo(stock + 5);
    }

    @Test
    void roomAnimalsTypeAndCountAreChecked() {
        assertThatThrownBy(() -> livestock.requestAnimals(sg, otto.getId(), "hus_00003", "COW_HOLSTEIN", 11))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("1 bis 10");
        livestock.requestAnimals(sg, otto.getId(), "hus_00003", "COW_HOLSTEIN", 8);
        actions.acceptCase(sg, livestock.cases(sg).getFirst().getId());
        assertThatThrownBy(() -> livestock.requestAnimals(sg, otto.getId(), "hus_00003", "COW_HOLSTEIN", 3))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Platz"); // 10 free - 8 on the way
        assertThatThrownBy(() -> livestock.requestAnimals(sg, otto.getId(), "hus_00003", "PIG_LANDRACE", 1))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Rasse");
        assertThatThrownBy(() -> livestock.requestAnimals(sg, greta.getId(), "hus_00003", "COW_HOLSTEIN", 1))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("keine Tiere dieser Art");
    }

    @Test
    void neighboursOfferAndAskOnTheirOwnAtMostOnceAMonth() {
        props.getFormulas().getLivestockTrade().setOfferProbabilityPerMonth(1);
        props.getFormulas().getLivestockTrade().setRequestProbabilityPerMonth(1);
        livestock.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        List<ServiceCase> list = livestock.cases(sg);
        assertThat(list).hasSize(1); // max-messages-per-month 1
        ServiceCase sc = list.getFirst();
        assertThat(sc.getCharacter().getId()).isEqualTo(otto.getId()); // the arable farm keeps no animals
        assertThat(sc.getQuantity()).isBetween(1, 10);
        assertThat(sc.getDirection()).startsWith("NEIGHBOR:");
    }

    @Test
    void anOlderModWithoutSubtypesBringsNoLivestockTrade() {
        facts(250_000, "");
        assertThat(livestock.stables(neighbors.latest(sg).orElseThrow())).isEmpty();
        props.getFormulas().getLivestockTrade().setOfferProbabilityPerMonth(1);
        livestock.onMonth(new GameMonthPassedEvent(sg.getId(), 1, sg.getCurrentGameTime()));
        assertThat(livestock.cases(sg)).isEmpty();
        assertThatThrownBy(() -> livestock.requestAnimals(sg, otto.getId(), "hus_00003", "COW_HOLSTEIN", 1))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Mod");
    }

    @Test
    void aRefusedPurchaseGivesTheStockBackAndAMissingAnimalDisappoints() {
        ServiceCase buy = livestock.requestAnimals(sg, otto.getId(), "hus_00003", "COW_HOLSTEIN", 3);
        int stock = livestock.stockOf(sg, otto, "COW");
        actions.acceptCase(sg, buy.getId());
        ack(ofType(buy, InstructionType.ANIMAL_TRANSFER), "FAILED", "NO_ANIMAL_SPACE");
        assertThat(buy.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(buy.getResolution()).isEqualTo("NO_ANIMAL_SPACE");
        assertThat(livestock.stockOf(sg, otto, "COW")).isEqualTo(stock);

        ServiceCase sell = livestock.offerAnimals(sg, otto.getId(), "hus_00003", "COW_ANGUS", 2);
        actions.acceptCase(sg, sell.getId());
        ack(ofType(sell, InstructionType.ANIMAL_TRANSFER), "FAILED", "NOT_ENOUGH_ANIMALS");
        assertThat(trust(otto)).contains(TrustReason.NEIGHBOR_DISAPPOINTED);
        assertThat(narrations()).contains("ANIMAL_TRADE_FAILED");
    }

    @Test
    void declinedAndIgnoredOffersCostTrustLikeTheGoodsTrade() {
        ServiceCase a = livestock.requestAnimals(sg, otto.getId(), "hus_00003", "COW_HOLSTEIN", 1);
        actions.declineCase(sg, a.getId());
        assertThat(trust(otto)).contains(TrustReason.NEIGHBOR_TRADE_DECLINED);
        ServiceCase b = livestock.offerAnimals(sg, otto.getId(), "hus_00003", "COW_HOLSTEIN", 1);
        sg.setCurrentGameTime(b.getDeadlineGameTime() + GameTime.days(1));
        livestock.onDay(new GameDayPassedEvent(sg.getId(), 99, sg.getCurrentGameTime()));
        assertThat(b.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(trust(otto)).contains(TrustReason.NEIGHBOR_TRADE_IGNORED);
    }
}
