package de.farmpulse.rpsim.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class MarketEventEngineTest {

    static final String CONTEXT = """
        { "savegameId": "%s", "mapName": "Erlengrund",
          "sellPoints": [{ "id": "MillNorth", "name": "Mühle Nord", "acceptedFillTypes": ["WHEAT","BARLEY","CANOLA","CORN"] },
                         { "id": "AgriTrade", "name": "Landhandel", "acceptedFillTypes": ["WHEAT","BARLEY","CANOLA","CORN"] }],
          "fillTypes": ["WHEAT","BARLEY","CANOLA","CORN"], "farmlands": [] }""";

    @Autowired Fixtures fx;
    @Autowired MarketEventEngine engine;
    @Autowired MarketEventRepository events;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired FactsService facts;
    @Autowired JsonMapper json;
    @Autowired RpsimProperties props;

    Savegame sg;
    MarketContext ctx;
    FarmFacts farm;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setMarketContextJson(CONTEXT.formatted(sg.getBridgeSavegameId()));
        fx.character(sg, CharacterRole.LAND_AGENT, CharacterCategory.DYNAMIC, "Landhändler Kuhn");
        fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Nachbar Jansen");
        fx.character(sg, CharacterRole.AUTHORITY, CharacterCategory.MANDATORY, "Amt Meyer");
        fx.snapshot(sg, 50_000); // storage: WHEAT only (42 000 l)
        ctx = facts.marketContext(sg).orElseThrow();
        farm = facts.latest(sg).orElseThrow();
    }

    @AfterEach
    void restore() {
        props.getFormulas().getMarket().setTypeWeights(new RpsimProperties().getFormulas().getMarket().getTypeWeights());
    }

    @Test
    void fillTypesInStockAreChosenStatisticallyMoreOften() {
        Map<String, Integer> counts = new HashMap<>();
        int n = 8000;
        for (int i = 0; i < n; i++) {
            counts.merge(engine.pickTarget(ctx, farm, Set.of()).orElseThrow().fillType(), 1, Integer::sum);
        }
        // weights: WHEAT 1 + 4 * 1.0 = 5, others 1 -> P(WHEAT) = 5/8
        assertThat(counts.get("WHEAT") / (double) n).isBetween(0.60, 0.65);
        for (String other : List.of("BARLEY", "CANOLA", "CORN")) {
            assertThat(counts.get(other) / (double) n).isBetween(0.10, 0.15);
        }
    }

    @Test
    void withoutStockAllFillTypesAreEquallyLikely() {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 8000; i++) {
            counts.merge(engine.pickTarget(ctx, null, Set.of()).orElseThrow().fillType(), 1, Integer::sum);
        }
        counts.values().forEach(c -> assertThat(c / 8000.0).isBetween(0.22, 0.28));
    }

    @Test
    void rumorsAreAboutSeventyPercentAccurate() {
        props.getFormulas().getMarket().setMaxActiveEvents(100000);
        int accurate = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            MarketEvent r = engine.spawnRumor(sg, ctx, farm).orElseThrow();
            if (Boolean.TRUE.equals(r.getIsAccurate())) {
                accurate++;
                MarketEvent real = events.findById(r.getReferencedEventId()).orElseThrow();
                assertThat(real.getStatus()).isEqualTo(MarketEventStatus.PLANNED);
                assertThat(real.getFillType()).isEqualTo(r.getFillType());
            } else {
                assertThat(r.getReferencedEventId()).isNull();
            }
        }
        assertThat(accurate / (double) n).isBetween(0.67, 0.73);
        props.getFormulas().getMarket().setMaxActiveEvents(3);
    }

    @Test
    void priceEventIsRegionalOneSellPointOnly() {
        MarketEvent ev = engine.spawnPriceEvent(sg, MarketEventType.DEMAND_SPIKE, ctx, farm, sg.getCurrentGameTime(), true)
                .orElseThrow();
        OutboxInstruction ins = outbox.findByInstructionId(ev.getInstructionId()).orElseThrow();
        var payload = json.readTree(ins.getPayloadJson());
        assertThat(payload.get("priceMode").asString()).isEqualTo("MULTIPLIER");
        assertThat(payload.get("sellPoint").asString()).isEqualTo(ev.getSellPoint());
        assertThat(payload.get("fillType").asString()).isEqualTo(ev.getFillType());
        // strength within the type band
        var band = props.getFormulas().getMarket().getBands().get("DEMAND_SPIKE");
        assertThat(ev.getPeakMultiplier()).isBetween(band.getMultiplierMin(), band.getMultiplierMax());
        // a second event never targets the same busy sell point / fill type pair
        for (int i = 0; i < 5; i++) {
            engine.spawnPriceEvent(sg, MarketEventType.DEMAND_SLUMP, ctx, farm, sg.getCurrentGameTime(), true)
                    .ifPresent(other -> assertThat(other.getSellPoint() + other.getFillType())
                            .isNotEqualTo(ev.getSellPoint() + ev.getFillType()));
        }
        assertThat(ev.getCharacter().getRole()).isEqualTo(CharacterRole.LAND_AGENT);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(j -> j.getEventType()).contains("MARKET_PRICE_EVENT");
    }

    @Test
    void subsidyIsAMoneyEventNotAPriceEvent() {
        MarketEvent ev = engine.spawnSubsidy(sg);
        OutboxInstruction ins = outbox.findByInstructionId(ev.getInstructionId()).orElseThrow();
        assertThat(ins.getType().name()).isEqualTo("MONEY_TRANSACTION");
        assertThat(ins.getPayloadJson()).contains("SUBSIDY");
        assertThat(ev.getCharacter().getRole()).isEqualTo(CharacterRole.AUTHORITY);
    }

    @Test
    void specialOfferNeedsParticipationDecisionThenBecomesFixedContract() {
        MarketEvent ev = engine.spawnSpecialOffer(sg, ctx, farm).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(MarketEventStatus.OFFERED);
        assertThat(ev.getInstructionId()).isNull();
        engine.decideParticipation(sg, ev.getId(), true);
        OutboxInstruction ins = outbox.findByInstructionId(ev.getInstructionId()).orElseThrow();
        assertThat(ins.getPayloadJson()).contains("\"priceMode\":\"FIXED\"").contains("maxQuantity");
        engine.onContractReported(new BridgeEvents.ContractReported(sg.getId(), ev.getInstructionId(), 8200,
                ev.getMaxQuantity(), "DEADLINE_REACHED"));
        assertThat(ev.getStatus()).isEqualTo(MarketEventStatus.ENDED);
        assertThat(ev.getDeliveredQuantity()).isEqualTo(8200);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).extracting(j -> j.getEventType()).contains("MARKET_CONTRACT_ENDED");
    }

    @Test
    void declinedSpecialOfferCreatesNoInstruction() {
        MarketEvent ev = engine.spawnSpecialOffer(sg, ctx, farm).orElseThrow();
        engine.decideParticipation(sg, ev.getId(), false);
        assertThat(ev.getStatus()).isEqualTo(MarketEventStatus.DECLINED);
        assertThat(ev.getInstructionId()).isNull();
    }

    @Test
    void concurrencyCapIsRespected() {
        for (int i = 0; i < 20; i++) {
            engine.spawn(sg);
        }
        assertThat(engine.activeCount(sg)).isLessThanOrEqualTo(props.getFormulas().getMarket().getMaxActiveEvents());
    }

    @Test
    void plannedEventWithAdvanceNoticeActivatesAtStart() {
        long start = sg.getCurrentGameTime() + GameTime.days(2);
        MarketEvent ev = engine.spawnPriceEvent(sg, MarketEventType.HARVEST_FAILURE, ctx, farm, start, true).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(MarketEventStatus.PLANNED);
        assertThat(outbox.findByInstructionId(ev.getInstructionId()).orElseThrow().getGameTimeEarliest()).isEqualTo(start);
        sg.setCurrentGameTime(start);
        engine.updateLifecycle(sg);
        assertThat(ev.getStatus()).isEqualTo(MarketEventStatus.ACTIVE);
        sg.setCurrentGameTime(ev.getEndGameTime());
        engine.updateLifecycle(sg);
        assertThat(ev.getStatus()).isEqualTo(MarketEventStatus.ENDED);
    }
}
