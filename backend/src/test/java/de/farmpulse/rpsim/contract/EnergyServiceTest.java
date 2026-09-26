package de.farmpulse.rpsim.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos.MarketContext;
import de.farmpulse.rpsim.bridge.BridgeDtos.SellPoint;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.NarrationJobRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** TODO T-20: energy supplier (fixed-price contracts / price fluctuations at biogas sell points). */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class EnergyServiceTest {

    static final String CONTEXT = """
        { "savegameId": "%s", "mapName": "Erlengrund",
          "sellPoints": [{ "id": "MillNorth", "name": "Mühle Nord", "acceptedFillTypes": ["WHEAT"] },
                         { "id": "BgaEast", "name": "Biogasanlage Ost", "acceptedFillTypes": ["SILAGE", "WHEAT"] }],
          "fillTypes": ["WHEAT", "SILAGE"], "farmlands": [] }""";

    @Autowired Fixtures fx;
    @Autowired EnergyService energy;
    @Autowired OutboxInstructionRepository outbox;
    @Autowired NarrationJobRepository jobs;
    @Autowired CharacterRepository characters;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setMarketContextJson(CONTEXT.formatted(sg.getBridgeSavegameId()));
        String facts = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 50_000)
                .replace("\"prices\": [", "\"prices\": [{ \"sellPoint\": \"BgaEast\", \"fillType\": \"SILAGE\", \"currentPrice\": 40 },");
        fx.snapshot(sg, sg.getCurrentGameTime(), 50_000, facts);
    }

    @AfterEach
    void restore() {
        props.getFormulas().setEnergy(new RpsimProperties.Energy());
    }

    private boolean supplierExists() {
        return characters.findAll().stream().anyMatch(c -> c.getSavegame().getId().equals(sg.getId())
                && c.getRole() == CharacterRole.ENERGY_SUPPLIER);
    }

    @Test
    void onlyPairsOfTheConfiguredFillTypes() {
        var ctx = new MarketContext("sg", "map", List.of(new SellPoint("A", "A", List.of("SILAGE", "WHEAT")),
                new SellPoint("B", "B", List.of("WHEAT"))), List.of(), List.of(), List.of());
        assertThat(EnergyService.energyPairs(ctx, List.of("SILAGE", "METHANE"))).containsExactly("A|SILAGE");
        assertThat(EnergyService.energyPairs(null, List.of("SILAGE"))).isEmpty();
    }

    @Test
    void withoutABiogasSellPointTheSupplierNeverAppears() {
        sg.setMarketContextJson(TestData.marketContext(sg.getBridgeSavegameId()));
        assertThat(energy.offer(sg)).isEmpty();
        assertThat(supplierExists()).isFalse();
    }

    @Test
    void fixedPriceContractAtTheBiogasPlantFromTheSupplier() {
        props.getFormulas().getEnergy().setContractShare(1);
        MarketEvent ev = energy.offer(sg).orElseThrow();
        assertThat(ev.getEventType()).isEqualTo(MarketEventType.SPECIAL_OFFER);
        assertThat(ev.getStatus()).isEqualTo(MarketEventStatus.OFFERED);
        assertThat(ev.getSellPoint()).isEqualTo("BgaEast");
        assertThat(ev.getFillType()).isEqualTo("SILAGE");
        assertThat(ev.getCharacter().getRole()).isEqualTo(CharacterRole.ENERGY_SUPPLIER);
        assertThat(jobs.findBySavegameOrderByIdAsc(sg)).filteredOn(j -> "MARKET_SPECIAL_OFFER".equals(j.getEventType()))
                .singleElement().extracting(NarrationJob::getCategory).isEqualTo(CommunicationCategory.ENERGY);
        assertThat(energy.offer(sg)).as("max-open 1").isEmpty();
    }

    @Test
    void priceFluctuationWhenNoContract() {
        props.getFormulas().getEnergy().setContractShare(0);
        MarketEvent ev = energy.offer(sg).orElseThrow();
        assertThat(ev.getEventType()).isIn(MarketEventType.DEMAND_SPIKE, MarketEventType.DEMAND_SLUMP);
        assertThat(ev.getSellPoint() + "|" + ev.getFillType()).isEqualTo("BgaEast|SILAGE");
        assertThat(outbox.findBySavegameOrderByIdAsc(sg)).extracting(OutboxInstruction::getPayloadJson)
                .anySatisfy(p -> assertThat(p).contains("MULTIPLIER").contains("BgaEast"));
    }

    @Test
    void withoutACurrentPriceOnlyAFluctuationIsPossible() {
        props.getFormulas().getEnergy().setContractShare(1);
        props.getFormulas().getEnergy().setFillTypes(List.of("DIGESTATE"));
        sg.setMarketContextJson(CONTEXT.formatted(sg.getBridgeSavegameId()).replace("\"SILAGE\", \"WHEAT\"", "\"DIGESTATE\""));
        MarketEvent ev = energy.offer(sg).orElseThrow();
        assertThat(ev.getEventType()).isNotEqualTo(MarketEventType.SPECIAL_OFFER);
        assertThat(ev.getFillType()).isEqualTo("DIGESTATE");
    }
}
