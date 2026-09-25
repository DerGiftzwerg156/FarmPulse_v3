package de.farmpulse.rpsim.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import de.farmpulse.rpsim.bridge.BridgeDtos.Assets;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeDtos.Price;
import de.farmpulse.rpsim.bridge.BridgeDtos.StorageEntry;
import de.farmpulse.rpsim.bridge.BridgeDtos.Vehicle;
import de.farmpulse.rpsim.config.RpsimProperties;
import org.junit.jupiter.api.Test;

/** Technical concept "Warenbestand-Bewertung": storageValue = Σ(amount · bestAvailablePrice), part of the assets. */
class StorageValuationTest {

    private final FactsService facts = new FactsService(null, null, new RpsimProperties());

    private static FarmFacts facts(List<StorageEntry> storage, List<Price> prices, List<Vehicle> vehicles) {
        return new FarmFacts(1, 0L, "sg", null, new Assets(vehicles, List.of(), List.of(), List.of(), storage), null, prices);
    }

    @Test
    void usesTheBestPricePerFillTypeAndThePriceUnit() {
        FarmFacts f = facts(
                List.of(new StorageEntry("WHEAT", 10_000.0, 20_000.0), new StorageEntry("CANOLA", 2_000.0, 5_000.0)),
                List.of(new Price("MillNorth", "WHEAT", 230.0), new Price("AgriTrade", "WHEAT", 212.0),
                        new Price("AgriTrade", "CANOLA", 425.0)),
                List.of());
        // 10,000 l * 230 €/1000 l + 2,000 l * 425 €/1000 l
        assertThat(facts.storageValue(f)).isEqualTo(2300.0 + 850.0);
    }

    @Test
    void fillTypeWithoutAnyPriceIsWorthNothing() {
        FarmFacts f = facts(List.of(new StorageEntry("OAT", 5_000.0, 5_000.0)), List.of(new Price("Mill", "WHEAT", 200.0)), List.of());
        assertThat(facts.storageValue(f)).isZero();
    }

    @Test
    void storageCountsIntoTheAssetSumLikeMachines() {
        List<Vehicle> tractor = List.of(new Vehicle("v1", 50_000.0, 90.0));
        FarmFacts empty = facts(List.of(), List.of(new Price("Mill", "WHEAT", 200.0)), tractor);
        FarmFacts full = facts(List.of(new StorageEntry("WHEAT", 100_000.0, 100_000.0)), List.of(new Price("Mill", "WHEAT", 200.0)), tractor);
        assertThat(facts.totalAssetValue(empty)).isEqualTo(50_000.0);
        assertThat(facts.totalAssetValue(full)).isEqualTo(50_000.0 + 20_000.0);
    }
}
