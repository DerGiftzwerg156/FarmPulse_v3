# File bridge protocol (mod ↔ backend)

Normative description of the four bridge files as implemented. Source: technical concept, chapter
"Datei-Bridge (Mod ↔ Backend)". All files are UTF-8 JSON and carry `savegameId`.

## Atomic writes

Writers write `<file>.tmp` and rename it over `<file>`. If `os.rename` is unavailable in the FS25 sandbox
the mod falls back to the marker strategy: `<file>.ready` is deleted, `<file>` written, `<file>.ready`
created. Readers must tolerate partially written files anyway: every reader validates the JSON and simply
retries on the next cycle.

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
  "liabilities": { "vanillaLoan": { "active": true, "remainingAmount": 80000 } },
  "prices": [{ "sellPoint": "MillNorth", "fillType": "WHEAT", "currentPrice": 215 }] }
```

- `gameTime`: in-game milliseconds since savegame start (stops while paused). 1 game day = 86 400 000.
- `condition`: 0–100 (100 = no damage).
- `storage`: classic silos only, aggregated per fill type (liters).
- `currentPrice`: price per 1000 liters currently paid at the sell point (incl. active RPSim events).

## `export/market_context.json` (mod → backend, on load + after each `FARMLAND_TRANSFER`)

```json
{ "savegameId": "...", "mapName": "Erlengrund",
  "sellPoints": [{ "id": "MillNorth", "name": "Mühle Nord", "acceptedFillTypes": ["BARLEY", "WHEAT"] }],
  "fillTypes": ["BARLEY", "WHEAT"],
  "farmlands": [{ "farmlandId": 12, "hectares": 4.5, "price": 54000, "ownerFarmId": 0 }] }
```

`ownerFarmId`: 0 = no owner in FS25 (unowned or owned by a tool NPC), otherwise the FS farm id.

## `import/instructions.json` (backend → mod)

```json
{ "savegameId": "...", "instructions": [ <envelope>, ... ] }
```

Common envelope fields: `instructionId` (unique), `type`, optional `batchId`, optional `gameTimeEarliest`,
optional `savegameId`.

| type | fields |
| --- | --- |
| `MONEY_TRANSACTION` | `amount` (signed), `reason` ∈ `CREDIT_DISBURSEMENT, CREDIT_INSTALLMENT, CREDIT_PENALTY, CREDIT_CALLBACK, SALARY_PAYMENT, EMPLOYEE_EFFECT, SUBSIDY, STARTING_CAPITAL_ADJUSTMENT, FARMLAND_PURCHASE, FARMLAND_SALE, OTHER`, `note` |
| `PRICE_EVENT` / `MULTIPLIER` | `fillType`, `sellPoint`, `peakMultiplier`, `rampUpHours`, `holdHours`, `decayHours` (start = `gameTimeEarliest` or time of application) |
| `PRICE_EVENT` / `FIXED` | `fillType`, `sellPoint`, `fixedPrice` (per 1000 l), `maxQuantity` (l), `deadlineGameTime`; precedence over `MULTIPLIER` for the same sell point/fill type |
| `FARMLAND_TRANSFER` | `farmlandId`, `direction` ∈ `TO_PLAYER, FROM_PLAYER`, `price` (reference only) |

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

`status` ∈ `APPLIED`, `REJECTED` (validation failed, `message` explains), `FAILED` (engine call failed).
`endReason` ∈ `DEADLINE_REACHED`, `MAX_QUANTITY_REACHED`. The file always contains every instruction still
inside the retention window (default 30 game days), so a missed file version never loses information.
Whether an instruction was executed is decided solely by the mod's persisted `processedInstructions` list.
