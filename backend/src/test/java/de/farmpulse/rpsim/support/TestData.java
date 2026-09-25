package de.farmpulse.rpsim.support;

import java.time.Instant;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.SavegameStatus;
import de.farmpulse.rpsim.domain.TonePreset;

/** Factory methods for test entities. */
public final class TestData {

    private TestData() {
    }

    public static Savegame activeSavegame(String bridgeId) {
        Savegame s = new Savegame();
        s.setBridgeSavegameId(bridgeId);
        s.setStatus(SavegameStatus.ACTIVE);
        s.setMapName("Erlengrund");
        s.setTonePreset(TonePreset.REALISTIC);
        s.setCreatedAt(Instant.now());
        s.setLinkedAt(Instant.now());
        s.setGenerationSeed(42);
        s.setStartingCapitalAdjusted(true);
        return s;
    }

    public static String farmFacts(String savegameId, long gameTime, long balance) {
        return """
            { "schemaVersion": 1, "gameTime": %d, "savegameId": "%s",
              "liquidity": { "balance": %d },
              "assets": {
                "vehicles": [{ "uniqueId": "veh_00042", "value": 285000, "condition": 82 }],
                "placeables": [{ "uniqueId": "plc_00011", "value": 120000 }],
                "farmland": [{ "farmlandId": 12, "hectares": 4.5, "price": 54000 }],
                "animals": [{ "husbandryUniqueId": "hus_00003", "type": "COW", "count": 24, "estimatedValue": 96000 }],
                "storage": [{ "fillType": "WHEAT", "amount": 42000, "capacity": 50000 }]
              },
              "liabilities": { "vanillaLoan": { "active": true, "remainingAmount": 80000 } },
              "prices": [{ "sellPoint": "MillNorth", "fillType": "WHEAT", "currentPrice": 215 },
                         { "sellPoint": "MillSouth", "fillType": "WHEAT", "currentPrice": 230 }]
            }""".formatted(gameTime, savegameId, balance);
    }

    public static String marketContext(String savegameId) {
        return """
            { "savegameId": "%s", "mapName": "Erlengrund",
              "sellPoints": [{ "id": "MillNorth", "name": "Mühle Nord", "acceptedFillTypes": ["WHEAT"] },
                             { "id": "MillSouth", "name": "Mühle Süd", "acceptedFillTypes": ["WHEAT"] }],
              "fillTypes": ["WHEAT"],
              "farmlands": [{ "farmlandId": 12, "hectares": 4.5, "price": 54000, "ownerFarmId": 1 },
                            { "farmlandId": 13, "hectares": 6, "price": 72000, "ownerFarmId": 0 }] }""".formatted(savegameId);
    }
}
