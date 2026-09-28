# File bridge protocol (mod ↔ backend)

Normative description of the four bridge files as implemented. Source: technical concept, chapter
"Datei-Bridge (Mod ↔ Backend)". All files are UTF-8 JSON and carry `savegameId`.

## Writing and reading

The FS25 Lua sandbox has no `os` module (`os.time`, `os.date`, `os.rename`, `os.remove` are missing - see the
FS25_UsedPlus AI coding reference, `pitfalls/what-doesnt-work.md`). The mod therefore writes every file directly
with `io.open` (write mode `direct`, the same approach as the FS25 Farm Dashboard mod) and creates no helper files.
The modes `rename`, `marker` and `auto` remain for tests/tools only. The backend and the simulator write
`<file>.tmp` and rename it over `<file>`.

Readers must tolerate partially written files: every reader validates the JSON and simply retries on the next
cycle (`BridgeFiles.read`).

## Timing

The mod starts the bridge only when the mission has started (`Mission00.onStartMission`; farms, vehicles and
placeables of the savegame do not exist earlier). The first export writes `market_context.json`,
`farm_facts.json` and `instructions_ack.json` immediately. The bridge folder is
`getUserProfileAppPath() .. "modSettings/FS25_RPSim/"` and is written to `log.txt` on load.

## `export/farm_facts.json` (mod → backend, every ~60 s)

```json
{ "schemaVersion": 1, "gameTime": 48300000, "savegameId": "map_erlengrund_1_20260101120000",
  "liquidity": { "balance": 245000 },
  "assets": {
    "vehicles":   [{ "uniqueId": "veh_00042", "value": 285000, "condition": 82 }],
    "placeables": [{ "uniqueId": "plc_00011", "value": 120000 }],
    "farmland":   [{ "farmlandId": 12, "hectares": 4.5, "price": 54000 }],
    "animals":    [{ "husbandryUniqueId": "hus_00003", "type": "COW", "count": 24, "estimatedValue": 96000 }],
    "storage":    [{ "fillType": "WHEAT", "amount": 42000, "capacity": 50000 }]
  },
  "liabilities": { "vanillaLoan": { "active": true, "remainingAmount": 80000 },
                   "leasing": [{ "uniqueId": "veh_00077" }] },
  "prices": [{ "sellPoint": "MillNorth", "fillType": "WHEAT", "currentPrice": 215, "trend": "CLIMBING" }],
  "calendar": { "period": 8, "dayInPeriod": 2, "daysPerPeriod": 3, "year": 2, "monotonicDay": 40,
                "periodName": "Oktober", "season": "AUTUMN" } }
```

- `gameTime`: in-game milliseconds since savegame start (stops while paused). 1 game day = 86 400 000.
- `vehicles`: only vehicles the farm **owns** (`VehiclePropertyState.OWNED`); leased vehicles are no assets.
- `liabilities.leasing`: leased vehicles (`VehiclePropertyState.LEASED`). `costPerPeriod` (per FS25 period) is
  optional and currently not exported - there is no verified FS25 API for per-vehicle leasing costs yet (manual
  test plan). The backend counts known costs as an obligation in the credit check.
- `condition`: 0–100 (100 = no damage).
- `storage`: classic silos only, aggregated per fill type (liters).
- `currentPrice`: price per 1000 liters currently paid at the sell point (incl. active RPSim events).
- `trend` (optional): price trend of the station as the game shows it - `SellingStation:getCurrentPricingTrend`
  bit flags `PRICE_CLIMBING` / `PRICE_FALLING` (as used by FS25_ProductionDirectSell) → `CLIMBING`, `FALLING`,
  `STABLE`.
- `missions` (optional, TODO T-22): vanilla contracts from `g_missionManager:getMissions()` - `AVAILABLE`
  (`MissionStatus.CREATED`, can be taken by anyone) and the player farm's own `RUNNING` (`PREPARING`/`RUNNING`) and
  `FINISHED` ones (`success` = `finishState == MissionFinishState.SUCCESS`), at most 50. Fields: `uniqueId`, `title`,
  `typeName` (`mission.type.name`), `field` (`mission.field:getName()`), `npcIndex` / `npcTitle` (`mission:getNPC()`,
  as used by FS25_BetterContracts), `reward` (`getReward()`). Read only - the tool never starts a contract.
- `calendar` (optional for older mods): `g_currentMission.environment` → `currentPeriod` (1..12, **period 1 =
  March**), `currentDayInPeriod` (1-based), `daysPerPeriod`, `currentYear`, `currentMonotonicDay`, plus
  `periodName` = `g_i18n:formatPeriod()` (localized month name). The backend's game month is this FS25 period:
  the current period started at `(monotonicDay - (dayInPeriod - 1)) * 86 400 000` in `gameTime` terms.
  "Days per period" can change at any time; scheduled dates keep their month.
  `season` (optional, TODO T-21): name of `environment.currentSeason` in the game's global `Season` table (looked up,
  not derived; `Season.WINTER` is used by FS25 BeehiveSystem / StonePickMission). The backend gives the narration a
  German date such as "Ende Oktober, Herbst, Jahr 2".
- Sell points: only real selling stations (`station:isa(SellingStation)`) that are not hidden from the prices menu
  (`hideFromPricesMenu`), in `farm_facts.json` and in `market_context.json`.

### Roadmap V2 blocks (optional, R2-Q1)

`farm_facts.json` gets five more **optional** blocks for [`ROADMAP_V2.md`](../../ROADMAP_V2.md). `schemaVersion` stays
`1` as long as every new block is optional. The contract is fixed now; the mod fills a block once the feature that
collects it is built (named per block). Until then the block is **missing**.

- **Missing ≠ empty.** A missing block means "not present" (older mod, or the feature is not built yet); the backend
  keeps its V1 behaviour then. An empty block (`"fields": []`, `"workedGameMs": {}`) is a real answer of the game.
  `BridgeDtos.FarmFacts` returns `null` for a missing block, `BridgeValidator` checks a block only when it is present.
- `RPSimFarmFacts.build` normalises what the adapter collects (`raw.finances`, `raw.workforce`, `raw.husbandries`,
  `raw.fields`, `raw.weather`): numbers are rounded, lists sorted, incomplete entries dropped. Ratios and weather
  values are rounded to 3 decimals.
- The bridge simulator exports the blocks only in the scenarios `helfer-hof`, `tierhof-krank` and `ernte-herbst`.

```json
{ "finances": { "periods": [{ "year": 2, "period": 8,
                              "byType": { "HARVEST_INCOME": 48200, "PURCHASE_FUEL": -3100, "AI": -1250,
                                          "RPSIM_SALARY_PAYMENT": -2400 } }] },
  "workforce": { "activeJobs": [{ "jobId": 7, "employeeId": 12, "title": "Fendt 942 Vario" }, { "jobId": 8 }],
                 "workedGameMs": { "12": 7200000 } },
  "husbandries": [{ "husbandryUniqueId": "hus_00003", "health": 61.5, "productivity": 0.42, "food": 0.2,
                    "conditions": [{ "title": "Wasser", "ratio": 0.05 }, { "title": "Stroh", "ratio": 0.2 }] }],
  "fields": [{ "farmlandId": 12, "name": "12", "hectares": 4.5, "fruitType": "WHEAT", "growthState": 5,
               "minHarvestingGrowthState": 7, "maxHarvestingGrowthState": 8, "weedState": 1, "stoneLevel": 0,
               "sprayLevel": 1, "limeLevel": 0, "plowLevel": 1, "groundType": "SOWN" }],
  "weather": { "raining": true, "rainFallScale": 0.6, "groundWetness": 0.7 } }
```

Sources: FS25 code dump `Dukefarming/FS25-lua-scripting` ("dump") and FS25 Community LUADOC
`umbraprior/FS25-Community-LUADOC` ("LUADOC"). 🟡 = the API exists, a detail can only be checked in the running game
([manual test plan, section 10](manual-test-plan.md#10-roadmap-v2-in-the-real-fs25)).

**`finances`** (R2-B1, booking journal). Required: `periods[]` with `year`, `period` (1..12) and `byType`.

| Field | Meaning | Source |
| --- | --- | --- |
| `periods[].year` / `.period` | FS25 year and period (period 1 = March) of the bookings, like `calendar` | `environment.currentYear` / `currentPeriod` (see `calendar`) |
| `periods[].byType` | Cumulative sum per money type in this period (signed, rounded). Key = name of the FS25 money type in the global `MoneyType` table, e.g. `HARVEST_INCOME`, `SOLD_PRODUCTS`, `MISSIONS`, `PURCHASE_FUEL`, `VEHICLE_RUNNING_COSTS`, `LEASING_COSTS`, `AI`; tool bookings as `RPSIM_<REASON>` | Hook on `Farm:changeBalance(amount, moneyType)` (LUADOC `script/Farms/Farm.md`); the categories appear as `g_currentMission:addMoney(..., MoneyType.X, ...)` in the game code (`AIJob.lua`, `Wearable`, `FillTrigger`, `Combine` …). 🟡 whether every booking passes `changeBalance` (fallback `FSBaseMission.addMoney`) and how the name of a money type is read (fallback: reverse lookup in `MoneyType`) |

Only the last `financeJournalPeriods` periods are kept (proposal 13, mod config of R2-B1). The sums are stored in the
savegame; after a reload without saving they jump back and the backend takes the new state as it is.

**`workforce`** (R2-A4). Required: `activeJobs[]` (each with `jobId`) and `workedGameMs`.

| Field | Meaning | Source |
| --- | --- | --- |
| `activeJobs[].jobId` | Id of the running FS25 helper job | `AIJob.jobId` (`AIJob:setId`, dump `ai/jobs/AIJob.lua`); jobs from `g_currentMission.aiSystem:getActiveJobs()` (LUADOC `script/AI/AISystem.md`) |
| `activeJobs[].employeeId` | Tool employee assigned to the job (R2-A2); missing = helper without employee (R2-D3) | mod state |
| `activeJobs[].title` | Title of the job; missing when the game returns an empty title | `job:getTitle()`: vehicle name for field work (`AIJobFieldWork:getTitle` → `vehicle:getName()`), helper name otherwise (`AIJob:getTitle` → `helper.title`) |
| `workedGameMs` | Cumulative game time (ms) each employee drove a helper; key = `employeeId` as text | game time between two exports (`RPSimGameAdapter:getGameTime()`), stored in the savegame |

**`husbandries[]`** (R2-A7). Required per entry: `husbandryUniqueId`, `health`, `food`, `conditions`.

| Field | Meaning | Source |
| --- | --- | --- |
| `husbandryUniqueId` | Same id as `assets.animals[].husbandryUniqueId`; animal type and head count stay there | placeable `uniqueId` (as in V1) |
| `health` | Mean `cluster.health` over all animal groups, 0..100 like the game's info box (`"%d %%"`) | `PlaceableHusbandryAnimals:updateInfo` (dump) |
| `productivity` | `getGlobalProductionFactor() * getProductionFactor()`; **missing** for horses and pigs, like in the game | `PlaceableHusbandryAnimals:getConditionInfos` (dump) |
| `food` | `getTotalFood() / getFoodCapacity()` | `PlaceableHusbandryFood.lua` (dump) |
| `conditions[]` | Every entry of `getConditionInfos()` with `title` (as shown in the game, localised) and `ratio` | `getConditionInfos` of `PlaceableHusbandryWater` (ratio clamped to 0..1), `…Straw`, `…LiquidManure`, `…Milk` (dump) |

**`fields[]`** (R2-C1). Only fields on farmlands the player owns. Required per entry: `farmlandId`, `name`,
`hectares`, `growthState`, `weedState`, `stoneLevel`, `sprayLevel`, `limeLevel`, `plowLevel`.

| Field | Meaning | Source |
| --- | --- | --- |
| `farmlandId` | Farmland of the field | `field.farmland` (dump `field/Field.lua`) |
| `name` | Field name as shown in the game | `field:getName()` (as for `missions[].field`) |
| `hectares` | Field area, 2 decimals | `field.areaHa` (dump `field/Field.lua`) |
| `fruitType` | Crop name; **missing** on a field without crop | `g_fruitTypeManager:getFruitTypeNameByIndex(state.fruitTypeIndex)` (dump `fruits/FruitTypeManager.lua`) |
| `minHarvestingGrowthState` / `maxHarvestingGrowthState` | Growth states in which the crop can be harvested; only with `fruitType` | `getFruitTypeByIndex(...)` fields of the same name (dump `FruitTypeDesc.lua`, `MapOverlayGenerator.lua`) |
| `growthState`, `weedState`, `stoneLevel`, `sprayLevel`, `limeLevel`, `plowLevel` | Raw levels of the field state (integers, units as in the game) | `FieldState` fields of the same name (dump `field/FieldState.lua`) via `field:getFieldState()` |
| `groundType` | Name of the ground type in the global `FieldGroundType` table (e.g. `SOWN`, `CULTIVATED`, `PLOWED`); optional | `FieldState.groundType` (dump); names as used in `FieldManager.lua` |

🟡 whether `field:getFieldState()` is updated soon after field work (the `FieldManager` walks the fields round-robin,
`fieldStateUpdateIndex`); fallback: own `FieldState.new()` sampled at the field centre (`posX`/`posZ`).

**`weather`** (R2-C2). All three fields required.

| Field | Meaning | Source |
| --- | --- | --- |
| `raining` | It is raining | `g_currentMission.environment.weather:getIsRaining()` (LUADOC: `BeehiveSystem`, `PlaceableSolarPanels`) |
| `rainFallScale` | Rain intensity, 0 = dry (the game tests `> 0`) | `weather:getRainFallScale()` (LUADOC: `VehicleSystem`, `Wipers`, `Combine`) |
| `groundWetness` | Ground wetness as the game uses it for wheels | `weather:getGroundWetness()` (LUADOC: `Wheels`, `Washable`) |

## `export/market_context.json` (mod → backend, on mission start, after each `FARMLAND_TRANSFER`, and on every `farm_facts` cycle when its content changed)

```json
{ "savegameId": "...", "mapName": "Erlengrund",
  "sellPoints": [{ "id": "MillNorth", "name": "Mühle Nord", "acceptedFillTypes": ["BARLEY", "WHEAT"] }],
  "fillTypes": ["BARLEY", "WHEAT"],
  "farmlands": [{ "farmlandId": 12, "hectares": 4.5, "price": 54000, "ownerFarmId": 0,
                  "showOnFarmlandsScreen": true, "defaultFarmProperty": false,
                  "npc": { "index": 3, "name": "NPC_OTTO", "title": "Otto Wendler" } }],
  "detectedMods": ["FS25_UsedPlus"] }
```

`ownerFarmId`: 0 = no owner in FS25 (unowned or owned by a tool NPC), otherwise the FS farm id.
`showOnFarmlandsScreen` / `defaultFarmProperty` (FS25 `Farmland.lua`): farmlands hidden in the vanilla farmland
menu (village, roads) or belonging to the map's default farm property are never given to NPCs, auctioned or
negotiated by the tool. `detectedMods`: installed mods whose features overlap with RPSim
(`g_modIsLoaded[...]` for `FS25_MarketDynamics`, `FS25_UsedPlus`, `FS25_EnhancedLoanSystem`,
`FS25_BetterContracts`; configurable as `conflictMods`) - the tool only warns.
`sellPoints[].production` / `ownedByPlayer` (optional, TODO T-22): the station belongs to a placeable with
`spec_productionPoint` (FS25 `PlaceableProductionPoint.lua`), i.e. a production point that buys goods; `ownedByPlayer`
marks the player's own productions. Delivery contracts are only offered at foreign productions.
`npc` (optional, TODO T-21): the FS25 NPC of the farmland (`Farmland.npcIndex` resolved with
`g_npcManager:getNPCByIndex`; `title` = name shown in the game, `name` = internal key). The backend lets this NPC own
the field as a village character (`rpsim.formulas.negotiation.use-game-npc-owners`) instead of inventing one.

## `import/instructions.json` + `import/instructions.xml` (backend → mod)

```json
{ "savegameId": "...", "instructions": [ <envelope>, ... ] }
```

The FS25 Lua sandbox refuses `io.open` in read mode ("io.open, only write mode ('w') is allowed"), so the mod
cannot read `instructions.json`. The backend therefore writes the same document a second time, as compact JSON
wrapped in XML, and the mod reads that file with the engine XML API (`XMLFile.loadIfExists` + `getString("rpsim.json")`):

```xml
<?xml version="1.0" encoding="utf-8" standalone="no"?>
<rpsim>
    <json>{"savegameId":"...","instructions":[...]}</json>
</rpsim>
```

`&`, `<` and `>` in the JSON text are escaped as XML entities. `instructions.json` stays for tooling (bridge
simulator, manual inspection). The optional mod config uses the same wrapper (`rpsim_config.xml`).

Common envelope fields: `instructionId` (unique), `type`, optional `batchId`, optional `gameTimeEarliest`,
optional `savegameId`.

| type | fields |
| --- | --- |
| `MONEY_TRANSACTION` | `amount` (signed), `reason` ∈ `CREDIT_DISBURSEMENT, CREDIT_INSTALLMENT, CREDIT_PENALTY, CREDIT_CALLBACK, SALARY_PAYMENT, EMPLOYEE_EFFECT, SUBSIDY, STARTING_CAPITAL_ADJUSTMENT, FARMLAND_PURCHASE, FARMLAND_SALE, OTHER`, since TODO T-20/T-22 also `INSURANCE_PREMIUM, INSURANCE_PAYOUT, DAMAGE, WILDLIFE_COMPENSATION, VET_INVOICE, LIVESTOCK_PREMIUM, LEASE_PAYMENT, MAINTENANCE_FEE`, since Roadmap V2 (R2-Q1) also `TAX_PAYMENT, TAX_REFUND, FINE, FAMILY, SPONSORING, COMPENSATION`; `note`. Each reason has its booking title `rpsim_money_<REASON>` in `modDesc.xml` |
| `PRICE_EVENT` / `MULTIPLIER` | `fillType`, `sellPoint`, `peakMultiplier`, `rampUpHours`, `holdHours`, `decayHours` (start = `gameTimeEarliest` or time of application) |
| `PRICE_EVENT` / `FIXED` | `fillType`, `sellPoint`, `fixedPrice` (per 1000 l), `maxQuantity` (l), `deadlineGameTime`; precedence over `MULTIPLIER` for the same sell point/fill type |
| `FARMLAND_TRANSFER` | `farmlandId`, `direction` ∈ `TO_PLAYER, FROM_PLAYER`, `price` (reference only) |
| `REPAIR_VEHICLE` (TODO T-22) | `vehicleId` (uniqueId of an own vehicle), optional `targetDamage` (0..1, R2-A6, default `0`). The mod calls `Wearable:setDamageAmount(min(targetDamage, getDamageAmount()), true)` - the damage part of the game's `repairVehicle()` without its repair booking (the maintenance contract pays); a repair never raises the damage. `FAILED` with `VEHICLE_NOT_FOUND`, `NOT_OWN_VEHICLE` or `NOT_WEARABLE`. Not re-sent after a rewind (the next monthly service repairs again). |
| `NOTIFICATION` (TODO T-21) | `text` (German, ≤ 120 characters), optional `level` ∈ `INFO, OK, CRITICAL` (`FSBaseMission.INGAME_NOTIFICATION_*`), optional `expiresAtGameTime`. Shown with `g_currentMission:addIngameNotification`; processed after `expiresAtGameTime` it is acknowledged `APPLIED` with `message: "EXPIRED"` and not shown. Never re-sent after a reload without saving, a failure creates no notice. |
| `EMPLOYEE_ROSTER` (Roadmap V2, R2-A0) | `employees[]` with `employeeId` (tool id, integer), `name`, `role` (`JobRole` name), `status` ∈ `ACTIVE, ON_LEAVE, STRIKE`; `helperWageMode` ∈ `EMPLOYEES, VANILLA` (R2-A1); `strictHelperLimit` (boolean, R2-A3). The complete list; the mod replaces its list (idempotent). **Validated since R2-Q1, executed with R2-A0** - until then the mod acknowledges it `FAILED` / `NOT_SUPPORTED`. |
| `PROMPT` (Roadmap V2, R2-F2) | `promptId`, `title`, `text`, optional `yesLabel` / `noLabel`, `expiresGameTime`. Yes/no question shown in the game; an expired prompt is dropped without being shown. **Validated since R2-Q1, executed with R2-F2** - until then the mod acknowledges it `FAILED` / `NOT_SUPPORTED`. |

**Batches:** instructions sharing a `batchId` are validated together and applied in the same cycle, or all
rejected. The backend always sends `FARMLAND_TRANSFER` + its `MONEY_TRANSACTION` as one batch.

**Pending:** an instruction whose `gameTimeEarliest` lies in the future stays in the file and is applied later.
The backend removes instructions from the file once they are acknowledged.

## `import/instructions_ack.json` (mod → backend)

```json
{ "savegameId": "...",
  "acks": [{ "instructionId": "ins_0231", "appliedAtGameTime": 48214000, "status": "APPLIED" }],
  "contractReports": [{ "instructionId": "ins_0298", "deliveredQuantity": 8200, "maxQuantity": 10000,
                        "endReason": "DEADLINE_REACHED" }] }
```

`status` ∈ `APPLIED`, `REJECTED` (validation failed, `message` explains), `FAILED` (not executed: engine call failed,
or `message` = `INSUFFICIENT_FUNDS` when the debits of the batch exceed the farm balance - the whole batch is then
not executed).
`endReason` ∈ `DEADLINE_REACHED`, `MAX_QUANTITY_REACHED`. The file always contains every instruction still
inside the retention window (default 30 game days), so a missed file version never loses information.
Whether an instruction was executed is decided solely by the mod's persisted `processedInstructions` list.

## Backend reactions

- **FAILED / REJECTED acks** (`FailedInstructionService`): loan installment → reversed and due again (escalation
  ladder), penalty → next escalation stage, call-back → loan `DEFAULTED` (collected as soon as liquidity allows),
  salary → stays due (salary delay logic), farmland deal → negotiation `FAILED`, ownership back to the previous
  owner. Every refused instruction raises a dashboard notice. After an `INSUFFICIENT_FUNDS` ack the snapshot
  balance is not trusted until a newer `farm_facts.json` arrived.
- **Savegame reloaded without saving** (`RewindService`): `farm_facts.json` with a game time earlier than the last
  snapshot is a rewind. The next `instructions_ack.json` rebuilt by the mod from the reloaded savegame shows which
  acknowledged instructions are missing; an ack file still written before the reload (it contains acks later than
  the reloaded point with their original time) is skipped. Missing `MONEY_TRANSACTION`, `PRICE_EVENT` and
  `FARMLAND_TRANSFER` instructions go back to `PENDING` with the **same** `instructionId`; the mod executes them
  again because they are not in its reloaded `processedInstructions`. Rewinds up to
  `rpsim.bridge.rewind-auto-resend-max-hours` are handled automatically, deeper ones wait for the player's decision
  on the dashboard. All other tool state (mails, trust, negotiations) is not rolled back.
