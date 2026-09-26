package de.farmpulse.rpsim.bridge;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** DTOs of the four bridge files (docs/dev/bridge-protocol.md). Raw states only. */
public final class BridgeDtos {

    private BridgeDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FarmFacts(Integer schemaVersion, Long gameTime, String savegameId, Liquidity liquidity, Assets assets,
                            Liabilities liabilities, List<Price> prices, Calendar calendar) {
    }

    /** TODO T-08: FS25 calendar of the savegame (game month = FS25 period, period 1 = March). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Calendar(Integer period, Integer dayInPeriod, Integer daysPerPeriod, Integer year, Long monotonicDay,
                           String periodName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Liquidity(Long balance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assets(List<Vehicle> vehicles, List<Placeable> placeables, List<OwnedFarmland> farmland,
                         List<Animal> animals, List<StorageEntry> storage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(String uniqueId, Double value, Double condition) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Placeable(String uniqueId, Double value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OwnedFarmland(Integer farmlandId, Double hectares, Double price) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Animal(String husbandryUniqueId, String type, Integer count, Double estimatedValue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StorageEntry(String fillType, Double amount, Double capacity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Liabilities(VanillaLoan vanillaLoan, List<LeasedVehicle> leasing) {
    }

    /**
     * T-04: leased vehicle (no asset). costPerPeriod (per FS25 period = game month) is optional until the FS25 API for
     * leasing costs is verified in the game.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeasedVehicle(String uniqueId, Double costPerPeriod) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VanillaLoan(Boolean active, Double remainingAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** trend (TODO T-10): CLIMBING / FALLING / STABLE as reported by the selling station, optional. */
    public record Price(String sellPoint, String fillType, Double currentPrice, String trend) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketContext(String savegameId, String mapName, List<SellPoint> sellPoints, List<String> fillTypes,
                                List<MapFarmland> farmlands, List<String> detectedMods) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SellPoint(String id, String name, List<String> acceptedFillTypes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /**
     * showOnFarmlandsScreen / defaultFarmProperty (TODO T-11): farmlands hidden in the vanilla farmland menu (village,
     * roads) are not buyable and never traded by the tool. Missing values (older mod) count as buyable.
     */
    public record MapFarmland(Integer farmlandId, Double hectares, Double price, Integer ownerFarmId,
                              Boolean showOnFarmlandsScreen, Boolean defaultFarmProperty) {

        public boolean tradeable() {
            return !Boolean.FALSE.equals(showOnFarmlandsScreen) && !Boolean.TRUE.equals(defaultFarmProperty);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AckDocument(String savegameId, List<Ack> acks, List<ContractReport> contractReports) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ack(String instructionId, Long appliedAtGameTime, String status, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractReport(String instructionId, Long deliveredQuantity, Long maxQuantity, String endReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InstructionsDocument(String savegameId, List<Object> instructions) {
    }
}
