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
                            Liabilities liabilities, List<Price> prices, Calendar calendar, List<Mission> missions) {

        public FarmFacts(Integer schemaVersion, Long gameTime, String savegameId, Liquidity liquidity, Assets assets,
                         Liabilities liabilities, List<Price> prices, Calendar calendar) {
            this(schemaVersion, gameTime, savegameId, liquidity, assets, liabilities, prices, calendar, null);
        }

        public List<Mission> missionList() {
            return missions == null ? List.of() : missions;
        }
    }

    /**
     * TODO T-22: vanilla contract (g_missionManager:getMissions): AVAILABLE (can be taken), RUNNING / FINISHED (the
     * player's own; success only for FINISHED). field = field number as shown in the game, npc = the client.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mission(String uniqueId, String status, String title, String typeName, String field, Integer npcIndex,
                          String npcTitle, Double reward, Boolean success) {
    }

    /** TODO T-08: FS25 calendar of the savegame (game month = FS25 period, period 1 = March). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Calendar(Integer period, Integer dayInPeriod, Integer daysPerPeriod, Integer year, Long monotonicDay,
                           String periodName, String season) {
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
    public record SellPoint(String id, String name, List<String> acceptedFillTypes, Boolean production,
                            Boolean ownedByPlayer) {

        public SellPoint(String id, String name, List<String> acceptedFillTypes) {
            this(id, name, acceptedFillTypes, null, null);
        }

        /** TODO T-22: a production point of the map (not the player's own) that buys goods. */
        public boolean foreignProduction() {
            return Boolean.TRUE.equals(production) && !Boolean.TRUE.equals(ownedByPlayer);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /**
     * showOnFarmlandsScreen / defaultFarmProperty (TODO T-11): farmlands hidden in the vanilla farmland menu (village,
     * roads) are not buyable and never traded by the tool. Missing values (older mod) count as buyable.
     */
    public record MapFarmland(Integer farmlandId, Double hectares, Double price, Integer ownerFarmId,
                              Boolean showOnFarmlandsScreen, Boolean defaultFarmProperty, GameNpc npc) {

        public boolean tradeable() {
            return !Boolean.FALSE.equals(showOnFarmlandsScreen) && !Boolean.TRUE.equals(defaultFarmProperty);
        }
    }

    /**
     * FS25 NPC of a farmland (TODO T-21): Farmland.npcIndex resolved with g_npcManager:getNPCByIndex; {@code title} is
     * the name the game shows, {@code name} the internal key. Missing for older mod versions.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameNpc(Integer index, String name, String title) {
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
