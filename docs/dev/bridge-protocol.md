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
                "periodName": "Oktober" } }
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
- `calendar` (optional for older mods): `g_currentMission.environment` → `currentPeriod` (1..12, **period 1 =
  March**), `currentDayInPeriod` (1-based), `daysPerPeriod`, `currentYear`, `currentMonotonicDay`, plus
  `periodName` = `g_i18n:formatPeriod()` (localized month name). The backend's game month is this FS25 period:
  the current period started at `(monotonicDay - (dayInPeriod - 1)) * 86 400 000` in `gameTime` terms.
  "Days per period" can change at any time; scheduled dates keep their month.
- Sell points: only real selling stations (`station:isa(SellingStation)`) that are not hidden from the prices menu
  (`hideFromPricesMenu`), in `farm_facts.json` and in `market_context.json`.

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
`npc` (optional, TODO T-21): the FS25 NPC of the farmland (`Farmland.npcIndex` resolved with
`g_npcManager:getNPCByIndex`; `title` = name shown in the game, `name` = internal key). The backend lets this NPC own
the field as a village character (`rpsim.formulas.negotiation.use-game-npc-owners`) instead of inventing one.

## `import/instructions.json` (backend → mod)

```json
{ "savegameId": "...", "instructions": [ <envelope>, ... ] }
```

Common envelope fields: `instructionId` (unique), `type`, optional `batchId`, optional `gameTimeEarliest`,
optional `savegameId`.

| type | fields |
| --- | --- |
| `MONEY_TRANSACTION` | `amount` (signed), `reason` ∈ `CREDIT_DISBURSEMENT, CREDIT_INSTALLMENT, CREDIT_PENALTY, CREDIT_CALLBACK, SALARY_PAYMENT, EMPLOYEE_EFFECT, SUBSIDY, STARTING_CAPITAL_ADJUSTMENT, FARMLAND_PURCHASE, FARMLAND_SALE, OTHER`, since TODO T-20/T-22 also `INSURANCE_PREMIUM, INSURANCE_PAYOUT, DAMAGE, WILDLIFE_COMPENSATION, VET_INVOICE, LIVESTOCK_PREMIUM, LEASE_PAYMENT, MAINTENANCE_FEE`; `note` |
| `PRICE_EVENT` / `MULTIPLIER` | `fillType`, `sellPoint`, `peakMultiplier`, `rampUpHours`, `holdHours`, `decayHours` (start = `gameTimeEarliest` or time of application) |
| `PRICE_EVENT` / `FIXED` | `fillType`, `sellPoint`, `fixedPrice` (per 1000 l), `maxQuantity` (l), `deadlineGameTime`; precedence over `MULTIPLIER` for the same sell point/fill type |
| `FARMLAND_TRANSFER` | `farmlandId`, `direction` ∈ `TO_PLAYER, FROM_PLAYER`, `price` (reference only) |
| `NOTIFICATION` (TODO T-21) | `text` (German, ≤ 120 characters), optional `level` ∈ `INFO, OK, CRITICAL` (`FSBaseMission.INGAME_NOTIFICATION_*`), optional `expiresAtGameTime`. Shown with `g_currentMission:addIngameNotification`; processed after `expiresAtGameTime` it is acknowledged `APPLIED` with `message: "EXPIRED"` and not shown. Never re-sent after a reload without saving, a failure creates no notice. |

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
