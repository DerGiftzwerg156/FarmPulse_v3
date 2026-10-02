package de.farmpulse.rpsim.farmwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
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
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3.1 R31-A1: the contractor works an own field (owner decisions 2026-10-02). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class ContractorWorkTest {

    /** Own fields: 2 ripe wheat (4 ha), 4 harvested barley, 5 empty without lime, 7 growing canola. */
    static final String FIELDS = """
            "fields": [
              { "farmlandId": 2, "name": "2", "hectares": 4.0, "fruitType": "WHEAT", "growthState": 8,
                "minHarvestingGrowthState": 8, "maxHarvestingGrowthState": 8, "withered": false, "cut": false,
                "fillType": "WHEAT", "litersPerSqm": 0.9, "weedState": 1, "stoneLevel": 0, "sprayLevel": 1,
                "limeLevel": 1, "plowLevel": 1 },
              { "farmlandId": 4, "name": "4", "hectares": 3.0, "fruitType": "BARLEY", "growthState": 10,
                "minHarvestingGrowthState": 9, "maxHarvestingGrowthState": 9, "withered": false, "cut": true,
                "fillType": "BARLEY", "litersPerSqm": 0.97, "weedState": 0, "stoneLevel": 0, "sprayLevel": 1,
                "limeLevel": 1, "plowLevel": 0 },
              { "farmlandId": 5, "name": "5", "hectares": 2.5, "growthState": 0, "weedState": 0, "stoneLevel": 0,
                "sprayLevel": 0, "limeLevel": 0, "plowLevel": 1 },
              { "farmlandId": 7, "name": "7", "hectares": 6.0, "fruitType": "CANOLA", "growthState": 3,
                "minHarvestingGrowthState": 7, "maxHarvestingGrowthState": 7, "withered": false, "cut": false,
                "fillType": "CANOLA", "litersPerSqm": 0.45, "weedState": 0, "stoneLevel": 0, "sprayLevel": 2,
                "limeLevel": 1, "plowLevel": 1 }],
            "fieldRules": { "plowingRequired": true, "limeRequired": true, "weedsEnabled": true, "stonesEnabled": true }""";

    static final String SILOS = """
            "tradeStorage": [{ "fillType": "WHEAT", "amount": 40000, "freeCapacity": 60000 }]""";

    @Autowired Fixtures fx;
    @Autowired ContractorWorkService contractor;
    @Autowired FailedInstructionService failed;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired TrustEventRepository trustEvents;
    @Autowired TrustScoreService trustScores;
    @Autowired DiaryEntryRepository diary;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    Character contractorCharacter;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        contractorCharacter = fx.character(sg, CharacterRole.CONTRACTOR, CharacterCategory.MANDATORY, "Lohnunternehmen Krüger");
        facts(250_000, FIELDS + ", " + SILOS);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setContractorWork(new RpsimProperties.ContractorWork());
    }

    private void facts(long balance, String extra) {
        fx.snapshot(sg, sg.getCurrentGameTime(), balance,
                TestData.withFields(TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), balance), extra));
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

    private void ack(OutboxInstruction ins, String status, String message) {
        ins.setStatus("APPLIED".equals(status) ? InstructionStatus.APPLIED : InstructionStatus.FAILED);
        ins.setAckMessage(message);
        var e = new BridgeEvents.InstructionAcked(sg.getId(), ins.getInstructionId(), status, ins.getRelatedEntityType(),
                ins.getRelatedEntityId(), Map.of());
        contractor.onAck(e);
        failed.onAck(e);
    }

    private void workDay(ServiceCase sc) {
        sg.setCurrentGameTime(sc.getDeadlineGameTime());
        contractor.onDay(new GameDayPassedEvent(sg.getId(), GameTime.dayIndex(sc.getDeadlineGameTime()),
                sc.getDeadlineGameTime()));
    }

    private ContractorWorkService.Option option(int farmlandId, String work) {
        return contractor.quote(sg, farmlandId).options().stream().filter(o -> o.work().equals(work)).findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------------------------------ formulas

    @Test
    void theYieldFactorFollowsFertilisationLimePlowAndWeeds() {
        var rules = new BridgeDtos.FieldRules(true, true, true, true);
        BridgeDtos.Field ok = new BridgeDtos.Field(1, "1", 1.0, "WHEAT", 8, 8, 8, 0, 0, 2, 1, 1, "SOWN");
        assertThat(contractor.yieldFactor(ok, rules)).isEqualTo(1.0);
        BridgeDtos.Field poor = new BridgeDtos.Field(1, "1", 1.0, "WHEAT", 8, 8, 8, 3, 0, 0, 0, 0, "SOWN");
        // 0.85 (no fertiliser) x 0.9 (lime) x 0.9 (plow) x (1 - 0.15 weeds)
        assertThat(contractor.yieldFactor(poor, rules)).isCloseTo(0.85 * 0.9 * 0.9 * 0.85,
                org.assertj.core.data.Offset.offset(1e-9));
        BridgeDtos.Field weedy = new BridgeDtos.Field(1, "1", 1.0, "WHEAT", 8, 8, 8, 9, 0, 2, 1, 1, "SOWN");
        assertThat(contractor.yieldFactor(weedy, rules)).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        // rules off: lime, plow and weeds do not count; without fieldRules every rule counts
        var off = new BridgeDtos.FieldRules(false, false, false, false);
        assertThat(contractor.yieldFactor(poor, off)).isEqualTo(0.85);
        assertThat(contractor.yieldFactor(poor, null)).isEqualTo(contractor.yieldFactor(poor, rules));
    }

    @Test
    void onlyWorksThatFitTheFieldAreOfferedAtThePricePerHectare() {
        var quote = contractor.quote(sg, 2);
        assertThat(quote.options()).extracting(ContractorWorkService.Option::work, ContractorWorkService.Option::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PLOW", "PHASE"),
                        org.assertj.core.groups.Tuple.tuple("CULTIVATE", "PHASE"),
                        org.assertj.core.groups.Tuple.tuple("LIME", "PHASE"),
                        org.assertj.core.groups.Tuple.tuple("SOW", "PHASE"),
                        org.assertj.core.groups.Tuple.tuple("HARVEST", null));
        ContractorWorkService.Option harvest = option(2, "HARVEST");
        assertThat(harvest.price()).isEqualTo(720); // 4 ha x 180 €
        // 4 ha x 10 000 m² x 0.9 l/m² x 0.95 (sprayLevel 1) x 0.95 (weedState 1)
        assertThat(harvest.harvestLiters()).isEqualTo(Math.round(Math.floor(40_000 * 0.9 * 0.95 * 0.95)));
        assertThat(option(4, "PLOW").reason()).isNull(); // harvested barley
        assertThat(option(4, "PLOW").price()).isEqualTo(330);
        assertThat(option(4, "LIME").reason()).isEqualTo("LIMED");
        assertThat(option(4, "SOW").reason()).isEqualTo("PHASE");
        assertThat(option(5, "SOW").reason()).isNull();
        assertThat(option(5, "LIME").reason()).isNull();
        assertThat(contractor.quote(sg, 7).options()).allSatisfy(o -> assertThat(o.possible()).isFalse());
        assertThat(contractor.quote(sg, 5).fruitTypes()).contains("WHEAT", "SORGHUM");
        assertThatThrownBy(() -> contractor.quote(sg, 12)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("kein eigenes Feld");
    }

    @Test
    void theWorkDayFollowsTheSeasonAndTheTrust() {
        assertThat(contractor.leadDays(sg, contractorCharacter)).containsExactly(1, 3);
        trustScores.recordEvent(contractorCharacter, 60, TrustReason.OTHER, "test");
        assertThat(contractor.leadDays(sg, contractorCharacter)).containsExactly(1, 2);
        props.getFormulas().getContractorWork().setHarvestPeriods(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
        assertThat(contractor.leadDays(sg, contractorCharacter)).containsExactly(2, 4);
    }

    // ------------------------------------------------------------------------------------------ order and work day

    @Test
    void aPlowingJobIsBookedOnlyOnTheWorkDayAndSettledByTheAck() {
        ServiceCase sc = contractor.order(sg, 4, "PLOW", null);
        assertThat(sc.getKind()).isEqualTo(CaseKind.CONTRACTOR_WORK);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS);
        assertThat(sc.getCharacter().getId()).isEqualTo(contractorCharacter.getId());
        assertThat(sc.getOfferAmount()).isEqualTo(330);
        assertThat(sc.getDeadlineGameTime() - sc.getGameTime()).isBetween(GameTime.days(1), GameTime.days(3));
        assertThat(instructions(sc)).isEmpty(); // nothing booked before the work day
        assertThatThrownBy(() -> contractor.order(sg, 4, "CULTIVATE", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("schon ein Auftrag");

        workDay(sc);
        OutboxInstruction work = ofType(sc, InstructionType.FIELD_WORK);
        OutboxInstruction money = ofType(sc, InstructionType.MONEY_TRANSACTION);
        assertThat(work.getBatchId()).isNotNull().isEqualTo(money.getBatchId());
        assertThat(json.readTree(work.getPayloadJson()).get("work").asString()).isEqualTo("PLOW");
        assertThat(json.readTree(work.getPayloadJson()).get("farmlandId").asInt()).isEqualTo(4);
        assertThat(json.readTree(work.getPayloadJson()).has("fruitType")).isFalse();
        assertThat(json.readTree(money.getPayloadJson()).get("amount").asLong()).isEqualTo(-330);
        assertThat(json.readTree(money.getPayloadJson()).get("reason").asString()).isEqualTo("CONTRACTOR_FEE");

        ack(work, "APPLIED", null);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
        assertThat(trustEvents.findByCharacterOrderByGameTimeAscIdAsc(contractorCharacter))
                .extracting(TrustEvent::getReason).contains(TrustReason.CONTRACTOR_WORK);
        assertThat(diary.findBySavegameOrderByGameTimeAscIdAsc(sg)).anySatisfy(d ->
                assertThat(d.getTitle()).isEqualTo("Lohnunternehmer: Pflügen auf Feld 4"));
        assertThat(narrations()).contains("CONTRACTOR_WORK_DONE");
    }

    @Test
    void aSowingJobCarriesTheFruitType() {
        assertThatThrownBy(() -> contractor.order(sg, 5, "SOW", "DRAGONFRUIT"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Fruchtsorte");
        ServiceCase sc = contractor.order(sg, 5, "SOW", "BARLEY");
        workDay(sc);
        assertThat(json.readTree(ofType(sc, InstructionType.FIELD_WORK).getPayloadJson()).get("fruitType").asString())
                .isEqualTo("BARLEY");
        assertThat(ofType(sc, InstructionType.MONEY_TRANSACTION)).isNotNull();
    }

    @Test
    void aHarvestGoesIntoTheSiloAndIsSettledWhenTheStorageIsAcknowledged() {
        ServiceCase sc = contractor.order(sg, 2, "HARVEST", null);
        long liters = sc.getQuantity();
        assertThat(sc.getTitle()).isEqualTo("WHEAT");
        workDay(sc);
        OutboxInstruction work = ofType(sc, InstructionType.FIELD_WORK);
        OutboxInstruction storage = ofType(sc, InstructionType.STORAGE_TRANSFER);
        assertThat(storage.getBatchId()).isEqualTo(work.getBatchId());
        assertThat(json.readTree(storage.getPayloadJson()).get("amount").asLong()).isEqualTo(liters);
        assertThat(json.readTree(storage.getPayloadJson()).get("direction").asString()).isEqualTo("IN");
        ack(work, "APPLIED", null);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.IN_PROGRESS); // waits for the storage
        ack(storage, "APPLIED", null);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.SETTLED);
    }

    @Test
    void aHarvestWithoutRoomInTheSilosIsRefusedBeforehand() {
        facts(250_000, FIELDS + ", \"tradeStorage\": [{ \"fillType\": \"WHEAT\", \"amount\": 95000, \"freeCapacity\": 5000 }]");
        assertThat(option(2, "HARVEST").reason()).isEqualTo("NO_CAPACITY");
        assertThatThrownBy(() -> contractor.order(sg, 2, "HARVEST", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Wohin mit dem Weizen?");
    }

    @Test
    void anOpenHarvestReservesItsLitresInTheSilos() {
        String ripe = """
                { "farmlandId": %d, "name": "%d", "hectares": 4.0, "fruitType": "WHEAT", "growthState": 8,
                  "minHarvestingGrowthState": 8, "maxHarvestingGrowthState": 8, "withered": false, "cut": false,
                  "fillType": "WHEAT", "litersPerSqm": 0.9, "weedState": 0, "stoneLevel": 0, "sprayLevel": 2,
                  "limeLevel": 1, "plowLevel": 1 }""";
        facts(250_000, "\"fields\": [" + ripe.formatted(2, 2) + ", " + ripe.formatted(3, 3) + "], "
                + "\"tradeStorage\": [{ \"fillType\": \"WHEAT\", \"amount\": 50000, \"freeCapacity\": 50000 }]");
        assertThat(option(3, "HARVEST").reason()).isNull(); // 36,000 l fit alone
        contractor.order(sg, 2, "HARVEST", null);
        // the second field would fit alone, but not after the first harvest
        assertThat(option(3, "HARVEST").reason()).isEqualTo("NO_CAPACITY");
    }

    @Test
    void noMoneyOnTheWorkDayCancelsTheJobWithoutBooking() {
        ServiceCase sc = contractor.order(sg, 4, "PLOW", null);
        facts(100, FIELDS + ", " + SILOS);
        workDay(sc);
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sc.getResolution()).isEqualTo("NO_FUNDS");
        assertThat(instructions(sc)).isEmpty();
        assertThat(narrations()).contains("CONTRACTOR_WORK_CANCELLED");
        assertThatThrownBy(() -> contractor.order(sg, 4, "PLOW", null)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Kontostand");
    }

    @Test
    void aFieldThatNoLongerFitsCancelsTheJob() {
        ServiceCase sc = contractor.order(sg, 5, "LIME", null);
        facts(250_000, FIELDS.replace("\"sprayLevel\": 0, \"limeLevel\": 0, \"plowLevel\": 1 }",
                "\"sprayLevel\": 0, \"limeLevel\": 2, \"plowLevel\": 1 }") + ", " + SILOS);
        workDay(sc);
        assertThat(sc.getResolution()).isEqualTo("NOT_NEEDED");
        assertThat(instructions(sc)).isEmpty();
    }

    @Test
    void anOlderModCancelsTheJobAndTheFeeIsNotReportedTwice() {
        ServiceCase sc = contractor.order(sg, 4, "PLOW", null);
        workDay(sc);
        OutboxInstruction work = ofType(sc, InstructionType.FIELD_WORK);
        OutboxInstruction money = ofType(sc, InstructionType.MONEY_TRANSACTION);
        ack(work, "FAILED", "NOT_SUPPORTED");
        ack(money, "FAILED", "BATCH_ABORTED: " + work.getInstructionId());
        assertThat(sc.getStatus()).isEqualTo(CaseStatus.EXPIRED);
        assertThat(sc.getResolution()).isEqualTo("MOD_OUTDATED");
        assertThat(narrations()).contains("CONTRACTOR_WORK_CANCELLED");
    }
}
