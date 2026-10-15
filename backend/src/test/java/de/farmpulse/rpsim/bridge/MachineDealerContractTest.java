package de.farmpulse.rpsim.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.notice.FailedInstructionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap vehicle dealer, section Q (contract): the optional delivery points (RFH-Q2) in farm_facts, the shop places
 * (RFH-Q2) in market_context, store_catalog.json (RFH-Q1), VEHICLE_DELIVERY as a type an older mod does not know
 * (RFH-Q3) and the classes of the new money reasons (RFH-Q4).
 */
class MachineDealerContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String FACTS = """
            "schemaVersion": 1, "gameTime": 48300000, "savegameId": "sg",
            "liquidity": { "balance": 245000 },
            "assets": { "vehicles": [], "placeables": [], "farmland": [], "animals": [], "storage": [] },
            "liabilities": { "vanillaLoan": { "active": false, "remainingAmount": 0 } },
            "prices": []""";

    private static final String CONTEXT = """
            "savegameId": "sg", "mapName": "Erlengrund", "sellPoints": [], "fillTypes": [], "farmlands": [],
            "detectedMods": []""";

    private static BridgeDtos.FarmFacts facts(String extra) {
        return JSON.readValue("{" + FACTS + (extra.isEmpty() ? "" : ", " + extra) + "}", BridgeDtos.FarmFacts.class);
    }

    private static BridgeDtos.MarketContext context(String extra) {
        return JSON.readValue("{" + CONTEXT + (extra.isEmpty() ? "" : ", " + extra) + "}",
                BridgeDtos.MarketContext.class);
    }

    @Test
    void deliveryPointsAreOptionalEmptyOrChecked() {
        assertThat(facts("").deliveryPoints()).isNull();
        assertThat(facts("\"deliveryPoints\": []").deliveryPoints()).isEmpty();
        var f = facts("""
                "deliveryPoints": [{ "seq": 1, "x": 10.13, "y": 101.5, "z": -20, "ry": 1.235, "farmlandId": 12,
                                     "day": 40, "dayTimeMs": 36000000 }, { "seq": 2, "x": 1, "y": 2, "z": 3, "ry": 0 }]""");
        assertThat(BridgeValidator.validate(f)).isEmpty();
        assertThat(f.deliveryPoints().get(0).farmlandId()).isEqualTo(12);
        assertThat(f.deliveryPoints().get(0).dayTimeMs()).isEqualTo(36_000_000L);
        assertThat(f.deliveryPoints().get(1).farmlandId()).isNull();
        for (String broken : new String[] {
                "{ \"seq\": 0, \"x\": 1, \"y\": 2, \"z\": 3, \"ry\": 0 }",
                "{ \"x\": 1, \"y\": 2, \"z\": 3, \"ry\": 0 }",
                "{ \"seq\": 1, \"x\": 1, \"y\": 2, \"z\": 3 }"}) {
            assertThat(BridgeValidator.validate(facts("\"deliveryPoints\": [" + broken + "]")))
                    .as(broken).singleElement().asString().startsWith("invalid delivery point");
        }
    }

    @Test
    void storePlacesAreOptionalInTheMarketContext() {
        assertThat(context("").storePlaces()).isNull();
        var ctx = context("\"storePlaces\": [{ \"x\": 100, \"z\": -5.1 }]");
        assertThat(BridgeValidator.validate(ctx)).isEmpty();
        assertThat(ctx.storePlaces().get(0).z()).isEqualTo(-5.1);
        assertThat(BridgeValidator.validate(context("\"storePlaces\": [{ \"x\": 1 }]")))
                .singleElement().asString().startsWith("invalid store place");
    }

    @Test
    void storeCatalogIsParsedAndChecked() {
        var c = JSON.readValue("""
                { "savegameId": "sg", "items": [
                  { "xmlFilename": "a.xml", "name": "724 Vario", "brand": "FENDT", "categoryName": "TRACTORSL",
                    "price": 215000, "dailyUpkeep": 120, "lifetime": 600, "isMod": false, "motorized": true,
                    "isBundle": false, "multipleItemPurchase": false,
                    "configurations": [{ "name": "baseColor", "title": "Farbe", "options": [
                        { "index": 1, "name": "Grün", "price": 0, "dailyUpkeep": 0, "isDefault": true,
                          "isSelectable": true, "isYesNoOption": false, "color": { "r": 0.1, "g": 0.5, "b": 0.2 },
                          "dependent": [] },
                        { "index": 2, "saveId": "BLACK", "name": "Schwarz", "desc": "Sonderlack", "price": 1500,
                          "dailyUpkeep": 1, "isDefault": false, "isSelectable": true, "isYesNoOption": false,
                          "dependent": [{ "name": "rimColor", "index": 2 }] }] }],
                    "defaultConfigurations": { "baseColor": 1 },
                    "configurationSets": [{ "name": "Profi", "isDefault": true, "configurations": { "baseColor": 2 } }] },
                  { "xmlFilename": "b.xml", "name": "Terradisc", "price": 26000, "configurations": [],
                    "defaultConfigurations": {}, "configurationSets": [] }] }""", BridgeDtos.StoreCatalog.class);
        assertThat(BridgeValidator.validate(c)).isEmpty();
        var item = c.items().get(0);
        assertThat(item.defaultConfigurations()).containsEntry("baseColor", 1);
        assertThat(item.configurations().get(0).options().get(1).dependent().get(0).name()).isEqualTo("rimColor");
        assertThat(item.configurations().get(0).options().get(0).color().g()).isEqualTo(0.5);
        assertThat(item.configurationSets().get(0).configurations()).isEqualTo(Map.of("baseColor", 2));

        var noItems = JSON.readValue("{ \"savegameId\": \"sg\" }", BridgeDtos.StoreCatalog.class);
        assertThat(BridgeValidator.validate(noItems)).containsExactly("items missing");
        var badItem = JSON.readValue("{ \"savegameId\": \"sg\", \"items\": [{ \"name\": \"x\", \"price\": 1 }] }",
                BridgeDtos.StoreCatalog.class);
        assertThat(BridgeValidator.validate(badItem)).singleElement().asString().startsWith("invalid catalog item");
        var badOption = JSON.readValue("""
                { "savegameId": "sg", "items": [{ "xmlFilename": "a.xml", "price": 1, "configurations": [
                    { "name": "wheel", "options": [{ "index": 0, "name": "x", "price": 0 }] }] }] }""",
                BridgeDtos.StoreCatalog.class);
        assertThat(BridgeValidator.validate(badOption)).singleElement().asString()
                .startsWith("invalid configuration of a.xml");
    }

    @Test
    void vehicleDeliveryCountsAsModOutdated() {
        assertThat(FailedInstructionService.ROADMAP_RFH_TYPES).containsExactly(InstructionType.VEHICLE_DELIVERY);
    }

    @Test
    void newMoneyReasonsHaveTheirFinanceClass() {
        var classes = new RpsimProperties().getFormulas().getFinance().getCategories();
        assertThat(classes).containsEntry("RPSIM_VEHICLE_DEPOSIT", RpsimProperties.FinanceClass.INVESTMENT)
                .containsEntry("RPSIM_VEHICLE_DELIVERY_FEE", RpsimProperties.FinanceClass.INVESTMENT)
                .containsEntry("RPSIM_VEHICLE_EXPRESS_FEE", RpsimProperties.FinanceClass.INVESTMENT)
                .containsEntry("RPSIM_VEHICLE_REFUND", RpsimProperties.FinanceClass.DIVESTMENT)
                .containsEntry("RPSIM_VEHICLE_CANCELLATION_FEE", RpsimProperties.FinanceClass.OPERATING_EXPENSE);
        assertThat(MoneyReason.valueOf("VEHICLE_CANCELLATION_FEE")).isNotNull();
    }
}
