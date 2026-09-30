package de.farmpulse.rpsim.bridge;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** DTOs of the four bridge files (docs/dev/bridge-protocol.md). Raw states only. */
public final class BridgeDtos {

    private BridgeDtos() {
    }

    /**
     * Roadmap V2 (R2-Q1): {@code finances}, {@code workforce}, {@code husbandries}, {@code fields} and {@code weather}
     * are optional. {@code null} means "not present" (the mod is too old or does not collect the block yet) and must
     * not be read as "empty": an empty block ({@code fields: []}) is a real answer of the game. Roadmap V3 (R3-Q1):
     * {@code npcFields} (R3-H1) and {@code tradeStorage} (R3-H2) follow the same rule. {@code missionLimitReached}
     * (R3-H5): the game's contract limit of the player farm (MissionManager:hasFarmReachedMissionLimit), null = unknown.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FarmFacts(Integer schemaVersion, Long gameTime, String savegameId, Liquidity liquidity, Assets assets,
                            Liabilities liabilities, List<Price> prices, Calendar calendar, List<Mission> missions,
                            Finances finances, Workforce workforce, List<Husbandry> husbandries, List<Field> fields,
                            Weather weather, FieldRules fieldRules, List<Field> npcFields,
                            List<TradeStorageEntry> tradeStorage, Boolean missionLimitReached) {

        /** Roadmap V3 R3-Q1 contract without the contract limit of R3-H5 (older mod). */
        public FarmFacts(Integer schemaVersion, Long gameTime, String savegameId, Liquidity liquidity, Assets assets,
                         Liabilities liabilities, List<Price> prices, Calendar calendar, List<Mission> missions,
                         Finances finances, Workforce workforce, List<Husbandry> husbandries, List<Field> fields,
                         Weather weather, FieldRules fieldRules, List<Field> npcFields,
                         List<TradeStorageEntry> tradeStorage) {
            this(schemaVersion, gameTime, savegameId, liquidity, assets, liabilities, prices, calendar, missions, finances,
                    workforce, husbandries, fields, weather, fieldRules, npcFields, tradeStorage, null);
        }

        /** Roadmap V2 contract without the blocks of Roadmap V3 (older mod). */
        public FarmFacts(Integer schemaVersion, Long gameTime, String savegameId, Liquidity liquidity, Assets assets,
                         Liabilities liabilities, List<Price> prices, Calendar calendar, List<Mission> missions,
                         Finances finances, Workforce workforce, List<Husbandry> husbandries, List<Field> fields,
                         Weather weather, FieldRules fieldRules) {
            this(schemaVersion, gameTime, savegameId, liquidity, assets, liabilities, prices, calendar, missions, finances,
                    workforce, husbandries, fields, weather, fieldRules, null, null);
        }

        public FarmFacts(Integer schemaVersion, Long gameTime, String savegameId, Liquidity liquidity, Assets assets,
                         Liabilities liabilities, List<Price> prices, Calendar calendar) {
            this(schemaVersion, gameTime, savegameId, liquidity, assets, liabilities, prices, calendar, null);
        }

        public FarmFacts(Integer schemaVersion, Long gameTime, String savegameId, Liquidity liquidity, Assets assets,
                         Liabilities liabilities, List<Price> prices, Calendar calendar, List<Mission> missions) {
            this(schemaVersion, gameTime, savegameId, liquidity, assets, liabilities, prices, calendar, missions,
                    null, null, null, null, null, null);
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

    /**
     * Roadmap V2 R2-B1: booking journal of the last FS25 periods. {@code byType} maps the FS25 money type (e.g.
     * {@code HARVEST_INCOME}) or {@code RPSIM_<REASON>} for tool bookings to the cumulative amount of the period.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finances(List<FinancePeriod> periods) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FinancePeriod(Integer year, Integer period, Map<String, Double> byType) {
    }

    /**
     * Roadmap V2 R2-A4: running FS25 helper jobs and the cumulative game time each employee drove a helper
     * ({@code workedGameMs}, key = employee id as text).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Workforce(List<ActiveJob> activeJobs, Map<String, Long> workedGameMs) {
    }

    /** {@code employeeId} is missing for a helper without an assigned employee (R2-D3). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActiveJob(Integer jobId, Long employeeId, String title) {
    }

    /**
     * Roadmap V2 R2-A7: state of one husbandry ({@code husbandryUniqueId} as in {@code assets.animals}). health = mean
     * cluster health (0..100 like the game's info box), productivity = production factor (missing for horses and
     * pigs), food = total food / capacity.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Husbandry(String husbandryUniqueId, Double health, Double productivity, Double food,
                            List<HusbandryCondition> conditions) {
    }

    /** One entry of the game's getConditionInfos (water, straw, slurry, milk ...), title as shown in the game. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HusbandryCondition(String title, Double ratio) {
    }

    /**
     * Roadmap V2 R2-C1: state of an own field (FS25 FieldState). {@code fruitType}, the harvesting growth states and the
     * crop details ({@code withered}, {@code cut}, {@code fillType}, {@code litersPerSqm}; older mods omit them) are
     * missing on a field without a crop.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(Integer farmlandId, String name, Double hectares, String fruitType, Integer growthState,
                        Integer minHarvestingGrowthState, Integer maxHarvestingGrowthState, Integer weedState,
                        Integer stoneLevel, Integer sprayLevel, Integer limeLevel, Integer plowLevel, String groundType,
                        Boolean withered, Boolean cut, String fillType, Double litersPerSqm) {

        /** Q contract without the crop details of R2-C (older mod). */
        public Field(Integer farmlandId, String name, Double hectares, String fruitType, Integer growthState,
                     Integer minHarvestingGrowthState, Integer maxHarvestingGrowthState, Integer weedState,
                     Integer stoneLevel, Integer sprayLevel, Integer limeLevel, Integer plowLevel, String groundType) {
            this(farmlandId, name, hectares, fruitType, growthState, minHarvestingGrowthState, maxHarvestingGrowthState,
                    weedState, stoneLevel, sprayLevel, limeLevel, plowLevel, groundType, null, null, null, null);
        }
    }

    /**
     * Roadmap V2 R2-C: game settings of the soil mechanics - the game shows "needs plowing" / "needs lime", weeds and
     * stones only when active. Missing block (older mod) = unknown.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldRules(Boolean plowingRequired, Boolean limeRequired, Boolean weedsEnabled, Boolean stonesEnabled) {
    }

    /**
     * Roadmap V2 R2-C2: current weather (environment.weather getIsRaining / getRainFallScale / getGroundWetness).
     * Hof-Tablet: {@code temperature} in °C (weather:getCurrentTemperature); missing with an older mod.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Weather(Boolean raining, Double rainFallScale, Double groundWetness, Double temperature) {

        /** R2-C2 contract without the temperature (older mod). */
        public Weather(Boolean raining, Double rainFallScale, Double groundWetness) {
            this(raining, rainFallScale, groundWetness, null);
        }
    }

    /**
     * Roadmap V3 R3-H2 (contract R3-Q1): tradeable goods = fill level ({@code amount}, l) and free capacity (l) of one
     * fill type summed over the own silos and silo extensions only. An entry means an own silo accepts the fill type.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TradeStorageEntry(String fillType, Double amount, Double freeCapacity) {
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

    /** Roadmap V3 (R3-Q1): {@code storeVehicles} (R3-V1) is optional; null = not present (older mod or switched off). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketContext(String savegameId, String mapName, List<SellPoint> sellPoints, List<String> fillTypes,
                                List<MapFarmland> farmlands, List<String> detectedMods, List<StoreVehicle> storeVehicles) {

        /** Contract without the vehicle catalog (older mod). */
        public MarketContext(String savegameId, String mapName, List<SellPoint> sellPoints, List<String> fillTypes,
                             List<MapFarmland> farmlands, List<String> detectedMods) {
            this(savegameId, mapName, sellPoints, fillTypes, farmlands, detectedMods, null);
        }
    }

    /**
     * Roadmap V3 R3-V1 (contract R3-Q1): vehicle of the shop catalog (g_storeManager:getItems(), species VEHICLE, shown in
     * the shop). {@code price} = list price, {@code lifetime} = the store item's lifetime, {@code motorized} = engine
     * present (storeItem.specs.power); motorized is missing when the mod could not read the specs.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoreVehicle(String xmlFilename, String name, Double price, Double lifetime, String categoryName,
                               Boolean isMod, Boolean motorized) {
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

    /** Roadmap V3 (R3-Q1): {@code result} = optional result of the action (vehicleId, missionId); null = none. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ack(String instructionId, Long appliedAtGameTime, String status, String message,
                      Map<String, Object> result) {

        public Ack(String instructionId, Long appliedAtGameTime, String status, String message) {
            this(instructionId, appliedAtGameTime, status, message, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractReport(String instructionId, Long deliveredQuantity, Long maxQuantity, String endReason) {
    }

    /**
     * instructions.json. Roadmap V2 R2-F1: {@code ackedResponses} = answers the backend processed (the mod removes them
     * from player_responses.json), {@code withdrawnPrompts} = questions no longer open (the mod drops them).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InstructionsDocument(String savegameId, List<Object> instructions,
                                       @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> ackedResponses,
                                       @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> withdrawnPrompts) {

        public InstructionsDocument(String savegameId, List<Object> instructions) {
            this(savegameId, instructions, List.of(), List.of());
        }
    }

    /** Roadmap V2 R2-F1: export/player_responses.json. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerResponsesDocument(String savegameId, List<PlayerAnswer> responses) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerAnswer(String responseId, String promptId, String answer, Long gameTime) {
    }
}
