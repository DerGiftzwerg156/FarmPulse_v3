package de.farmpulse.rpsim.field;

import java.util.Arrays;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.support.TestData;

/** farm_facts documents with the Roadmap V2 R2-C blocks (fields, fieldRules, weather, calendar). */
final class FieldDocs {

    private FieldDocs() {
    }

    static final String ALL_RULES = "{ \"plowingRequired\": true, \"limeRequired\": true, \"weedsEnabled\": true, "
            + "\"stonesEnabled\": true }";

    /** A field on farmland 12 (4.5 ha). crop == null = no crop. */
    static String field(int farmlandId, String crop, int growthState, Boolean withered, Boolean cut, int weeds,
                        int stones, int lime, int plow) {
        String cropPart = crop == null ? "" : """
                "fruitType": "%s", "minHarvestingGrowthState": 8, "maxHarvestingGrowthState": 8, "fillType": "%s",
                "litersPerSqm": 0.9, %s%s""".formatted(crop, crop,
                withered == null ? "" : "\"withered\": " + withered + ", ", cut == null ? "" : "\"cut\": " + cut + ", ");
        return """
                { "farmlandId": %d, "name": "%d", "hectares": 4.5, %s"growthState": %d, "weedState": %d, "stoneLevel": %d,
                  "sprayLevel": 1, "limeLevel": %d, "plowLevel": %d }""".formatted(farmlandId, farmlandId, cropPart,
                growthState, weeds, stones, lime, plow);
    }

    static String growing(String crop, int growthState) {
        return field(12, crop, growthState, false, false, 0, 0, 1, 1);
    }

    static String doc(String savegameId, long gameTime, int year, String rules, Boolean raining, String... fields) {
        StringBuilder extra = new StringBuilder("\"calendar\": { \"period\": 1, \"dayInPeriod\": 1, \"daysPerPeriod\": 1, "
                + "\"year\": " + year + ", \"monotonicDay\": " + gameTime / 86_400_000L + " }");
        if (fields != null) {
            extra.append(", \"fields\": [").append(Arrays.stream(fields).collect(Collectors.joining(","))).append("]");
        }
        if (rules != null) {
            extra.append(", \"fieldRules\": ").append(rules);
        }
        if (raining != null) {
            extra.append(", \"weather\": { \"raining\": ").append(raining)
                    .append(", \"rainFallScale\": 0.5, \"groundWetness\": 0.5 }");
        }
        return TestData.withFields(TestData.farmFacts(savegameId, gameTime, 100_000), extra.toString());
    }
}
