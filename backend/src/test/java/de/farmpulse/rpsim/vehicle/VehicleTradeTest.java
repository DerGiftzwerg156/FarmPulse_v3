package de.farmpulse.rpsim.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationDirection;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.OfferResult;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.VehicleDeal;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V3 R3-V2 / R3-V3: used machines bought from the workshop or a neighbour and own machines sold. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class VehicleTradeTest {

    static final long DAY = GameTime.days(1);
    static final String CONTEXT = """
            { "savegameId": "%s", "mapName": "Erlengrund", "sellPoints": [], "fillTypes": [], "farmlands": [],
              "storeVehicles": [
                { "xmlFilename": "data/vehicles/fendt/vario700.xml", "name": "Fendt 700 Vario", "price": 245000,
                  "lifetime": 600, "categoryName": "TRACTORSL", "isMod": false, "motorized": true },
                { "xmlFilename": "data/vehicles/fendt/vario1000.xml", "name": "Fendt 1050", "price": 520000,
                  "lifetime": 600, "categoryName": "TRACTORSL", "isMod": false, "motorized": true },
                { "xmlFilename": "data/vehicles/tools/pallet.xml", "name": "Palettengabel", "price": 2000,
                  "lifetime": 600, "isMod": false, "motorized": false },
                { "xmlFilename": "data/vehicles/tools/nolife.xml", "name": "Ohne Lebensdauer", "price": 9000,
                  "isMod": false } ] }""";

    @Autowired Fixtures fx;
    @Autowired VehicleTradeService trade;
    @Autowired NegotiationEngine engine;
    @Autowired FailedInstructionService failed;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired RpsimProperties props;
    @Autowired JsonMapper json;

    Savegame sg;
    Character workshop;
    Character jansen;
    Character albers;
    Character villager;

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
        albers = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Bäuerin Albers");
        villager = fx.character(sg, CharacterRole.VILLAGER, CharacterCategory.DYNAMIC, "Erna Klatsch");
        // own tractor veh_00042 (value 285,000) with its name
        fx.snapshot(sg, sg.getCurrentGameTime(), 1_000_000, TestData.farmFacts(sg.getBridgeSavegameId(),
                sg.getCurrentGameTime(), 1_000_000).replace("\"condition\": 82 }",
                "\"condition\": 82, \"name\": \"Deutz-Fahr 6165\", \"xmlFilename\": \"data/vehicles/deutz/6165.xml\" }"));
    }

    @AfterEach
    void restore() {
        props.getFormulas().setUsedVehicle(new RpsimProperties.UsedVehicle());
    }

    private RpsimProperties.UsedVehicle cfg() {
        return props.getFormulas().getUsedVehicle();
    }

    private List<String> narrations() {
        return jobs.findBySavegameOrderByIdAsc(sg).stream().map(j -> j.getEventType()).toList();
    }

    private List<OutboxInstruction> of(InstructionType type) {
        return outbox.findBySavegameOrderByIdAsc(sg).stream().filter(o -> o.getType() == type).toList();
    }

    private JsonNode payload(OutboxInstruction o) {
        return json.readTree(o.getPayloadJson());
    }

    private Negotiation negotiation(VehicleDeal d) {
        return trade.negotiationsOf(sg, d).getFirst();
    }

    /** The mod refused an instruction (as the bridge does: ack message, then the event). */
    private void fail(OutboxInstruction o, String message) {
        o.setAckMessage(message);
        failed.onAck(new BridgeEvents.InstructionAcked(sg.getId(), o.getInstructionId(), "FAILED",
                o.getRelatedEntityType(), o.getRelatedEntityId()));
    }

    private void applied(OutboxInstruction o, Map<String, Object> result) {
        trade.onAck(new BridgeEvents.InstructionAcked(sg.getId(), o.getInstructionId(), "APPLIED",
                o.getRelatedEntityType(), o.getRelatedEntityId(), result));
    }

    // ------------------------------------------------------------------------------------------ formula

    @Test
    void theUsedPriceFollowsTheGameFormula() {
        RpsimProperties.UsedVehicle c = cfg();
        // hour factor 1 − 300 / 600 = 0.5, age factor −0.1·ln 2 + 0.75 = 0.6807
        assertThat(VehicleTradeService.usedPrice(100_000, 600, true, 24, 300, c)).isEqualTo(34_034);
        assertThat(VehicleTradeService.usedPrice(100_000, 600, null, 24, 300, c)).isEqualTo(34_034); // unknown = motor
        // without an engine: hours ^ 1.3
        assertThat(VehicleTradeService.usedPrice(100_000, 600, false, 24, 100, c))
                .isCloseTo(Math.round(100_000 * (1 - Math.pow(100, 1.3) / 600) * (-0.1 * Math.log(2) + 0.75)), within(1L));
        // the age factor is capped at 0.85 (young machines), the price never below 3 % of the list price
        assertThat(VehicleTradeService.usedPrice(100_000, 600, true, 3, 0, c)).isEqualTo(85_000);
        assertThat(VehicleTradeService.usedPrice(100_000, 600, true, 0, 0, c)).isEqualTo(85_000);
        assertThat(VehicleTradeService.usedPrice(100_000, 600, true, 120, 600, c)).isEqualTo(3_000);
        // hours for a target hour factor
        assertThat(VehicleTradeService.hoursFor(0.5, 600, true, c)).isEqualTo(300);
        int h = VehicleTradeService.hoursFor(0.6, 600, false, c);
        assertThat(1 - Math.pow(h, 1.3) / 600).isCloseTo(0.6, within(0.01));
    }

    // ------------------------------------------------------------------------------------------ purchase

    @Test
    void aMonthStartBringsOneOfferOfACatalogMachineInTheRanges() {
        cfg().setOfferProbability(1);
        trade.onMonth(new GameMonthPassedEvent(sg.getId(), 1, DAY));
        trade.onMonth(new GameMonthPassedEvent(sg.getId(), 2, 2 * DAY)); // at most one open offer
        List<VehicleDeal> all = trade.list(sg);
        assertThat(all).hasSize(1);
        VehicleDeal d = all.getFirst();
        assertThat(d.getDirection()).isEqualTo(VehicleDeal.BUY);
        assertThat(d.getStoreXmlFilename()).isEqualTo("data/vehicles/fendt/vario700.xml"); // the only one in range
        assertThat(d.getAgeMonths()).isBetween(12, 120);
        double hourFactor = 1 - d.getOperatingHours() / 600.0;
        assertThat(hourFactor).isBetween(0.3, 0.9 + 1.0 / 600);
        assertThat(d.getDamage()).isBetween(0.0, 0.3);
        assertThat(d.getWear()).isBetween(0.0, 0.5);
        assertThat(d.getGamePrice()).isEqualTo(VehicleTradeService.usedPrice(245_000, 600, true, d.getAgeMonths(),
                d.getOperatingHours(), cfg()));
        double factor = VehicleDeal.WORKSHOP.equals(d.getSellerKind()) ? 1.1 : 0.95;
        assertThat(d.getBasePrice()).isEqualTo(Math.round(d.getGamePrice() * factor));
        assertThat(d.getCharacter()).isIn(workshop, jansen, albers);
        Negotiation n = negotiation(d);
        assertThat(n.getKind()).isEqualTo(NegotiationKind.DIRECT);
        assertThat(n.getDirection()).isEqualTo(NegotiationDirection.PLAYER_BUYS);
        assertThat(n.getLastCounterOffer()).isEqualTo(d.getBasePrice());
        assertThat(n.getClosesAtGameTime()).isEqualTo(sg.getCurrentGameTime() + 7 * DAY);
        assertThat(n.getMaxRounds()).isEqualTo(3);
        assertThat(narrations()).containsExactly("VEHICLE_OFFER");
    }

    @Test
    void theWorkshopAddsItsMarkupAndANeighbourGivesADiscount() {
        cfg().setWorkshopShare(1);
        VehicleDeal w = trade.offer(sg).orElseThrow();
        assertThat(w.getSellerKind()).isEqualTo(VehicleDeal.WORKSHOP);
        assertThat(w.getCharacter()).isEqualTo(workshop);
        assertThat(w.getBasePrice()).isEqualTo(Math.round(w.getGamePrice() * 1.1));
        cfg().setWorkshopShare(0);
        VehicleDeal nb = trade.offer(sg).orElseThrow();
        assertThat(nb.getSellerKind()).isEqualTo(VehicleDeal.NEIGHBOR);
        assertThat(nb.getCharacter()).isIn(jansen, albers);
        assertThat(nb.getBasePrice()).isEqualTo(Math.round(nb.getGamePrice() * 0.95));
    }

    @Test
    void anAgreementDeliversTheMachineAndTheModBooksThePrice() {
        VehicleDeal d = trade.offer(sg).orElseThrow();
        Negotiation n = negotiation(d);
        assertThat(engine.placeOffer(sg, n.getId(), d.getBasePrice()).result()).isEqualTo(OfferResult.ACCEPTED);
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.AGREED);
        assertThat(d.getFinalPrice()).isEqualTo(d.getBasePrice());
        OutboxInstruction spawn = of(InstructionType.VEHICLE_SPAWN).getFirst();
        JsonNode p = payload(spawn);
        assertThat(p.get("storeXmlFilename").asString()).isEqualTo(d.getStoreXmlFilename());
        assertThat(p.get("ageMonths").asInt()).isEqualTo(d.getAgeMonths());
        assertThat(p.get("operatingHours").asInt()).isEqualTo(d.getOperatingHours());
        assertThat(p.get("price").asLong()).isEqualTo(d.getBasePrice());
        assertThat(p.get("moneyReason").asString()).isEqualTo("VEHICLE_PURCHASE");
        assertThat(spawn.getBatchId()).isNull(); // the mod books the price itself
        assertThat(of(InstructionType.MONEY_TRANSACTION)).isEmpty();
        assertThat(narrations()).containsExactly("VEHICLE_OFFER", "VEHICLE_DEAL_AGREED");
        applied(spawn, Map.of("vehicleId", "veh_00043"));
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.DONE);
        assertThat(d.getVehicleId()).isEqualTo("veh_00043");
    }

    @Test
    void noSpaceIsRetriedDailyUpToFiveTimesThenTheDealFails() {
        VehicleDeal d = trade.offer(sg).orElseThrow();
        engine.placeOffer(sg, negotiation(d).getId(), d.getBasePrice());
        for (int attempt = 1; attempt <= 5; attempt++) {
            List<OutboxInstruction> spawns = of(InstructionType.VEHICLE_SPAWN);
            assertThat(spawns).hasSize(attempt);
            assertThat(d.getAttempts()).isEqualTo(attempt);
            fail(spawns.getLast(), "NO_SPACE");
            if (attempt < 5) {
                assertThat(d.getStatus()).isEqualTo(VehicleDeal.AGREED);
                assertThat(d.getNextAttemptGameTime()).isEqualTo(sg.getCurrentGameTime() + DAY);
                trade.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime())); // not yet
                assertThat(of(InstructionType.VEHICLE_SPAWN)).hasSize(attempt);
                sg.setCurrentGameTime(sg.getCurrentGameTime() + DAY);
                trade.onDay(new GameDayPassedEvent(sg.getId(), 0, sg.getCurrentGameTime()));
            }
        }
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.FAILED);
        assertThat(d.getFailureReason()).isEqualTo("NO_SPACE");
        assertThat(of(InstructionType.NOTIFICATION)).hasSize(4);
        assertThat(narrations()).filteredOn("VEHICLE_NO_SPACE"::equals).hasSize(4);
        assertThat(narrations().getLast()).isEqualTo("VEHICLE_DEAL_FAILED");
    }

    @Test
    void anUnknownShopItemEndsTheDealAtOnce() {
        VehicleDeal d = trade.offer(sg).orElseThrow();
        engine.placeOffer(sg, negotiation(d).getId(), d.getBasePrice());
        fail(of(InstructionType.VEHICLE_SPAWN).getFirst(), "UNKNOWN_STORE_ITEM");
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.FAILED);
        assertThat(d.getFailureReason()).isEqualTo("UNKNOWN_STORE_ITEM");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("UNKNOWN_STORE_ITEM");
    }

    @Test
    void anOfferThatRunsOutEndsTheDeal() {
        VehicleDeal d = trade.offer(sg).orElseThrow();
        sg.setCurrentGameTime(sg.getCurrentGameTime() + 8 * DAY);
        engine.expireDeadlines(sg);
        trade.onDay(new GameDayPassedEvent(sg.getId(), 18, sg.getCurrentGameTime()));
        assertThat(negotiation(d).getStatus()).isEqualTo(NegotiationStatus.EXPIRED);
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.ENDED);
        assertThat(trade.offer(sg)).isPresent(); // a new offer may come
    }

    @Test
    void noOfferWithoutACatalog() {
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        assertThat(trade.offer(sg)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ sale

    @Test
    void neighboursAnswerASaleOfferWithinTheCap() {
        VehicleDeal d = trade.offerForSale(sg, "veh_00042", 330_000);
        assertThat(d.getDirection()).isEqualTo(VehicleDeal.SELL);
        assertThat(d.getVehicleName()).isEqualTo("Deutz-Fahr 6165");
        assertThat(d.getGamePrice()).isEqualTo(285_000);
        List<Negotiation> offers = trade.negotiationsOf(sg, d);
        assertThat(offers).hasSizeBetween(1, 2);
        for (Negotiation n : offers) {
            assertThat(n.getKind()).isEqualTo(NegotiationKind.SALE_OFFER);
            assertThat(n.getDirection()).isEqualTo(NegotiationDirection.PLAYER_SELLS);
            assertThat(n.getLastCounterOffer()).isBetween(285_000L, 313_500L);
            assertThat(engine.vehicleMaxAccept(n)).isEqualTo(313_500); // 110 % of the game value
        }
        assertThat(narrations()).filteredOn("VEHICLE_SALE_OFFER"::equals).hasSize(offers.size());
        assertThatThrownBy(() -> trade.offerForSale(sg, "veh_00042", 300_000)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("bereits");
        assertThatThrownBy(() -> trade.offerForSale(sg, "veh_none", 300_000)).isInstanceOf(BusinessRuleException.class);
        // above the cap: a counter offer at the cap, then the sale at the cap
        Negotiation n = offers.getFirst();
        var outcome = engine.placeOffer(sg, n.getId(), 330_000);
        assertThat(outcome.result()).isEqualTo(OfferResult.COUNTER);
        assertThat(outcome.counterAmount()).isEqualTo(313_500);
        assertThat(engine.placeOffer(sg, n.getId(), 313_500).result()).isEqualTo(OfferResult.ACCEPTED);
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.AGREED);
        assertThat(d.getCharacter()).isEqualTo(n.getCounterpartCharacter());
        offers.stream().filter(o -> o != n).forEach(o -> assertThat(o.getStatus()).isEqualTo(NegotiationStatus.EXPIRED));
        OutboxInstruction remove = of(InstructionType.VEHICLE_REMOVE).getFirst();
        OutboxInstruction money = of(InstructionType.MONEY_TRANSACTION).getFirst();
        assertThat(payload(remove).get("vehicleId").asString()).isEqualTo("veh_00042");
        assertThat(payload(money).get("amount").asLong()).isEqualTo(313_500);
        assertThat(payload(money).get("reason").asString()).isEqualTo("VEHICLE_SALE");
        assertThat(money.getBatchId()).isEqualTo(remove.getBatchId());
        assertThat(remove.getId()).isLessThan(money.getId()); // the removal first
        applied(remove, Map.of());
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.DONE);
        assertThat(narrations().getLast()).isEqualTo("VEHICLE_SOLD_GOSSIP");
        assertThat(jobs.findBySavegameOrderByIdAsc(sg).getLast().getFactsJson()).contains("Deutz-Fahr 6165");
    }

    @Test
    void anAttachedMachineIsNotSoldAndTheGameShowsAHint() {
        VehicleDeal d = trade.offerForSale(sg, "veh_00042", 285_000);
        Negotiation n = negotiation(d);
        engine.placeOffer(sg, n.getId(), 285_000);
        fail(of(InstructionType.VEHICLE_REMOVE).getFirst(), "VEHICLE_ATTACHED");
        assertThat(d.getStatus()).isEqualTo(VehicleDeal.FAILED);
        assertThat(d.getFailureReason()).isEqualTo("VEHICLE_ATTACHED");
        assertThat(payload(of(InstructionType.NOTIFICATION).getFirst()).get("text").asString())
                .contains("Bitte erst abkoppeln");
        assertThat(narrations().getLast()).isEqualTo("VEHICLE_DEAL_FAILED");
        // an older mod: "unknown type" ends the deal as MOD_OUTDATED
        assertThat(VehicleTradeService.reasonOf("unknown type VEHICLE_REMOVE")).isEqualTo("MOD_OUTDATED");
        assertThat(VehicleTradeService.reasonOf("BATCH_ABORTED: ins_1")).isEqualTo("BATCH_ABORTED");
    }

    @Test
    void noSaleWithoutNeighbours() {
        jansen.setStatus(de.farmpulse.rpsim.domain.CharacterStatus.TERMINATED);
        albers.setStatus(de.farmpulse.rpsim.domain.CharacterStatus.TERMINATED);
        assertThatThrownBy(() -> trade.offerForSale(sg, "veh_00042", 300_000)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Nachbarn");
    }
}
