package de.farmpulse.rpsim.tablet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.Initiator;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationDirection;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.FarmlandOwnershipRepository;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/** Roadmap V3.1 R31-K1: the field outlines with owner, phase and symbols (owner decisions 2026-10-06). */
@SpringBootTest
@Transactional
@Import(Fixtures.class)
class FieldMapServiceTest {

    static final String SQUARE = "[{\"x\":0,\"z\":0},{\"x\":100,\"z\":0},{\"x\":100,\"z\":100}]";
    static final String CONTEXT = """
            { "savegameId": "%s", "mapName": "Erlengrund", "sellPoints": [], "fillTypes": [], "farmlands": [],
              "fieldShapes": { "mapSize": 2048, "fields": [
                { "farmlandId": 12, "name": "12", "points": %s },
                { "farmlandId": 13, "name": "13", "points": %s },
                { "farmlandId": 14, "name": "14", "points": %s },
                { "farmlandId": 15, "name": "15", "points": %s },
                { "farmlandId": 16, "name": "16", "points": %s } ] } }""";
    static final String FIELDS = """
            "calendar": { "period": 5, "dayInPeriod": 1, "daysPerPeriod": 1, "year": 2, "monotonicDay": 10 },
            "fields": [{ "farmlandId": 12, "name": "12", "hectares": 4.5, "fruitType": "WHEAT", "growthState": 8,
                         "minHarvestingGrowthState": 7, "maxHarvestingGrowthState": 9 },
                       { "farmlandId": 13, "name": "13", "hectares": 6, "fruitType": "BARLEY", "growthState": 3,
                         "minHarvestingGrowthState": 7, "maxHarvestingGrowthState": 9 }]""";

    @Autowired Fixtures fx;
    @Autowired FieldMapService map;
    @Autowired de.farmpulse.rpsim.field.FieldService fieldService;
    @Autowired FarmlandOwnershipRepository ownership;
    @Autowired ServiceCaseRepository cases;
    @Autowired NegotiationRepository negotiations;

    Savegame sg;
    Character neighbor;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        neighbor = fx.character(sg, CharacterRole.NEIGHBOR_FARMER, CharacterCategory.DYNAMIC, "Hauke Harms");
    }

    private void own(int id, OwnerType type, Character c, boolean leasedToPlayer, boolean leasedFromPlayer) {
        FarmlandOwnership o = new FarmlandOwnership();
        o.setSavegame(sg);
        o.setFarmlandId(id);
        o.setOwnerType(type);
        o.setOwnerCharacter(c);
        o.setReferencePrice(50_000);
        o.setHectares(5);
        o.setLeasedToPlayer(leasedToPlayer);
        o.setLeasedFromPlayer(leasedFromPlayer);
        ownership.save(o);
    }

    @Test
    void withoutOutlinesTheMapIsEmpty() {
        FieldMapService.FieldMap m = map.map(sg);
        assertThat(m.mapSize()).isNull();
        assertThat(m.fields()).isEmpty();
    }

    @Test
    void everyOutlineCarriesOwnerPhaseAndSymbols() {
        sg.setMarketContextJson(CONTEXT.formatted(sg.getBridgeSavegameId(), SQUARE, SQUARE, SQUARE, SQUARE, SQUARE));
        own(12, OwnerType.PLAYER, null, false, false);
        own(13, OwnerType.CHARACTER, neighbor, true, false); // leased by the player
        own(14, OwnerType.CHARACTER, neighbor, false, false);
        own(15, OwnerType.PLAYER, null, false, true); // leased out
        // 16: no owner in the tool
        var snap = fx.snapshot(sg, sg.getCurrentGameTime(), 100_000, TestData.withFields(
                TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100_000), FIELDS));
        fieldService.onFacts(new de.farmpulse.rpsim.bridge.BridgeEvents.FactsIngested(sg.getId(), snap.getId(),
                sg.getCurrentGameTime(), false));
        ServiceCase order = new ServiceCase();
        order.setSavegame(sg);
        order.setKind(CaseKind.CONTRACTOR_WORK);
        order.setStatus(CaseStatus.IN_PROGRESS);
        order.setFarmlandId(13);
        order.setGameTime(sg.getCurrentGameTime());
        order.setCreatedAt(Instant.now());
        cases.save(order);
        Negotiation auction = new Negotiation();
        auction.setSavegame(sg);
        auction.setAssetType(AssetType.FARMLAND);
        auction.setAssetId("14");
        auction.setKind(NegotiationKind.AUCTION);
        auction.setDirection(NegotiationDirection.PLAYER_BUYS);
        auction.setInitiatedBy(Initiator.SYSTEM);
        auction.setStatus(NegotiationStatus.OPEN);
        auction.setBasePrice(50_000);
        auction.setMaxRounds(3);
        auction.setOpenedAtGameTime(sg.getCurrentGameTime());
        negotiations.save(auction);

        FieldMapService.FieldMap m = map.map(sg);
        assertThat(m.mapSize()).isEqualTo(2048);
        Map<Integer, FieldMapService.MapField> f = m.fields().stream()
                .collect(Collectors.toMap(FieldMapService.MapField::farmlandId, Function.identity()));
        assertThat(f.get(12).kind()).isEqualTo("OWN");
        assertThat(f.get(12).phase()).isEqualTo("HARVESTABLE");
        assertThat(f.get(12).fruitType()).isEqualTo("WHEAT");
        assertThat(f.get(12).hints()).containsExactly("HARVESTABLE");
        assertThat(f.get(12).points()).hasSize(3);
        assertThat(f.get(13).kind()).isEqualTo("OWN");
        assertThat(f.get(13).leased()).isTrue();
        assertThat(f.get(13).phase()).isEqualTo("GROWING");
        assertThat(f.get(13).orders()).containsExactly("CONTRACTOR_WORK");
        assertThat(f.get(14).kind()).isEqualTo("NEIGHBOR");
        assertThat(f.get(14).ownerName()).isEqualTo("Hauke Harms");
        assertThat(f.get(14).auction()).isTrue();
        assertThat(f.get(14).phase()).isNull();
        assertThat(f.get(15).leasedOut()).isTrue();
        assertThat(f.get(16).kind()).isEqualTo("FREE");
        assertThat(f.get(16).ownerName()).isNull();
    }
}
