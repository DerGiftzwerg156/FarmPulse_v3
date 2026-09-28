package de.farmpulse.rpsim.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Roadmap V2 (R2-Q1): optional farm_facts blocks - missing ("not present") vs. empty vs. invalid. */
class BridgeValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String V1 = """
            "schemaVersion": 1, "gameTime": 48300000, "savegameId": "sg",
            "liquidity": { "balance": 245000 },
            "assets": { "vehicles": [], "placeables": [], "farmland": [], "animals": [], "storage": [] },
            "liabilities": { "vanillaLoan": { "active": false, "remainingAmount": 0 } },
            "prices": []""";

    private static FarmFacts facts(String extra) {
        return JSON.readValue("{" + V1 + (extra.isEmpty() ? "" : ", " + extra) + "}", FarmFacts.class);
    }

    @Test
    void anOlderModWithoutTheNewBlocksIsValidAndTheBlocksAreNotPresent() {
        FarmFacts f = facts("");
        assertThat(BridgeValidator.validate(f)).isEmpty();
        assertThat(f.finances()).isNull();
        assertThat(f.workforce()).isNull();
        assertThat(f.husbandries()).isNull();
        assertThat(f.fields()).isNull();
        assertThat(f.weather()).isNull();
    }

    @Test
    void emptyBlocksAreKeptApartFromMissingOnes() {
        FarmFacts f = facts("""
                "finances": { "periods": [] }, "workforce": { "activeJobs": [], "workedGameMs": {} },
                "husbandries": [], "fields": []""");
        assertThat(BridgeValidator.validate(f)).isEmpty();
        assertThat(f.finances().periods()).isEmpty();
        assertThat(f.workforce().activeJobs()).isEmpty();
        assertThat(f.workforce().workedGameMs()).isEmpty();
        assertThat(f.husbandries()).isEmpty();
        assertThat(f.fields()).isEmpty();
        assertThat(f.weather()).isNull();
    }

    @Test
    void completeBlocksAreParsed() {
        FarmFacts f = facts("""
                "finances": { "periods": [{ "year": 2, "period": 8,
                    "byType": { "HARVEST_INCOME": 48200, "PURCHASE_FUEL": -3100, "RPSIM_SALARY_PAYMENT": -2400 } }] },
                "workforce": { "activeJobs": [{ "jobId": 7, "employeeId": 12, "title": "Fendt 942 Vario" }, { "jobId": 8 }],
                               "workedGameMs": { "12": 7200000 } },
                "husbandries": [{ "husbandryUniqueId": "hus_00003", "health": 61.5, "food": 0.2,
                                  "conditions": [{ "title": "Wasser", "ratio": 0.05 }] }],
                "fields": [{ "farmlandId": 12, "name": "12", "hectares": 4.5, "fruitType": "WHEAT", "growthState": 5,
                             "minHarvestingGrowthState": 7, "maxHarvestingGrowthState": 8, "weedState": 1,
                             "stoneLevel": 0, "sprayLevel": 1, "limeLevel": 0, "plowLevel": 1, "groundType": "SOWN",
                             "withered": false, "cut": false, "fillType": "WHEAT", "litersPerSqm": 0.9 }],
                "fieldRules": { "plowingRequired": true, "limeRequired": false, "weedsEnabled": true, "stonesEnabled": true },
                "weather": { "raining": true, "rainFallScale": 0.6, "groundWetness": 0.7 }""");
        assertThat(BridgeValidator.validate(f)).isEmpty();
        assertThat(f.finances().periods().get(0).byType()).containsEntry("PURCHASE_FUEL", -3100.0);
        assertThat(f.workforce().activeJobs().get(1).employeeId()).isNull();
        assertThat(f.workforce().workedGameMs()).containsEntry("12", 7_200_000L);
        assertThat(f.husbandries().get(0).productivity()).isNull();
        assertThat(f.fields().get(0).maxHarvestingGrowthState()).isEqualTo(8);
        assertThat(f.weather().rainFallScale()).isEqualTo(0.6);
        // Roadmap V2 R2-C: crop details and the soil settings of the savegame
        assertThat(f.fields().get(0).litersPerSqm()).isEqualTo(0.9);
        assertThat(f.fields().get(0).withered()).isFalse();
        assertThat(f.fieldRules().limeRequired()).isFalse();
    }

    @Test
    void invalidBlocksAreReported() {
        assertThat(BridgeValidator.validate(facts("\"finances\": {}"))).containsExactly("finances.periods missing");
        assertThat(BridgeValidator.validate(facts(
                "\"finances\": { \"periods\": [{ \"year\": 1, \"period\": 13, \"byType\": {} }] }")))
                .singleElement().asString().startsWith("invalid finance period");
        assertThat(BridgeValidator.validate(facts("\"workforce\": { \"activeJobs\": [] }")))
                .containsExactly("workforce.{activeJobs,workedGameMs} required");
        assertThat(BridgeValidator.validate(facts(
                "\"workforce\": { \"activeJobs\": [{ \"title\": \"x\" }], \"workedGameMs\": { \"1\": -5 } }")))
                .hasSize(2);
        assertThat(BridgeValidator.validate(facts(
                "\"husbandries\": [{ \"husbandryUniqueId\": \"h\", \"health\": 50, \"food\": 0.5 }]")))
                .singleElement().asString().startsWith("invalid husbandry");
        assertThat(BridgeValidator.validate(facts(
                "\"fields\": [{ \"farmlandId\": 1, \"name\": \"1\", \"hectares\": 2, \"growthState\": 0 }]")))
                .singleElement().asString().startsWith("invalid field");
        assertThat(BridgeValidator.validate(facts("\"weather\": { \"raining\": true, \"rainFallScale\": -1, \"groundWetness\": 0 }")))
                .singleElement().asString().startsWith("invalid weather");
        assertThat(BridgeValidator.validate(facts("\"fieldRules\": { \"plowingRequired\": true }")))
                .singleElement().asString().startsWith("invalid fieldRules");
    }
}
