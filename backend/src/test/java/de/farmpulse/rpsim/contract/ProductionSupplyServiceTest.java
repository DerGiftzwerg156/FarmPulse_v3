package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO T-22: delivery contracts with production points of the map. */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class ProductionSupplyServiceTest {

    static final String CONTEXT = """
        { "savegameId": "%s", "mapName": "Erlengrund",
          "sellPoints": [{ "id": "MillNorth", "name": "Mühle Nord", "acceptedFillTypes": ["WHEAT"] },
                         { "id": "plc_bakery", "name": "Bäckerei", "acceptedFillTypes": ["WHEAT"], "production": true,
                           "ownedByPlayer": false },
                         { "id": "plc_own", "name": "Eigene Mühle", "acceptedFillTypes": ["WHEAT"], "production": true,
                           "ownedByPlayer": true }],
          "fillTypes": ["WHEAT"], "farmlands": [] }""";

    @Autowired Fixtures fx;
    @Autowired ProductionSupplyService supply;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setMarketContextJson(CONTEXT.formatted(sg.getBridgeSavegameId()));
        String facts = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 50_000)
                .replace("\"prices\": [", "\"prices\": [{ \"sellPoint\": \"plc_bakery\", \"fillType\": \"WHEAT\", \"currentPrice\": 240 },"
                        + "{ \"sellPoint\": \"plc_own\", \"fillType\": \"WHEAT\", \"currentPrice\": 250 },");
        fx.snapshot(sg, sg.getCurrentGameTime(), 50_000, facts);
    }

    @Test
    void fixedPriceContractOnlyAtForeignProductions() {
        MarketEvent ev = supply.offer(sg).orElseThrow();
        assertThat(ev.getEventType()).isEqualTo(MarketEventType.SPECIAL_OFFER);
        assertThat(ev.getStatus()).isEqualTo(MarketEventStatus.OFFERED);
        assertThat(ev.getSellPoint()).isEqualTo("plc_bakery");
        assertThat(ev.getFixedPrice()).isGreaterThan(240);
        assertThat(supply.offer(sg)).as("max-open 1").isEmpty();
    }

    @Test
    void mapsWithoutProductionsGetNoOffer() {
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        assertThat(supply.offer(sg)).isEmpty();
    }
}
