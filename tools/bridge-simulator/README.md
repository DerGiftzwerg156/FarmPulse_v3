# Bridge simulator (development tool)

Simulates the FS25_RPSim mod without Farming Simulator 25: it writes `farm_facts.json` /
`market_context.json` exactly like the mod and consumes `instructions.json` with the same idempotency, batch
and `savegameId` semantics, writing `instructions_ack.json`. It is **not** part of the product.

## Why Node.js?

- Zero-build, fast iteration, runs everywhere the Angular toolchain already runs (Node is required for the
  frontend anyway), and the Playwright E2E tests / screenshot generator can start it as a child process.
- The simulator must be independent of backend DTOs on purpose: it validates its output against the JSON
  schemas in `schema/` (mirroring `docs/dev/bridge-protocol.md`), so a backend DTO change can never make the
  simulator "agree" with a broken backend.

## Start

```bash
cd tools/bridge-simulator
npm ci
node src/cli.js --scenario wohlhabender-hof
node src/cli.js --list-scenarios
node src/cli.js --help
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--dir` | `./runtime/modSettings/FS25_RPSim` | Bridge folder (the backend `dev` profile points here) |
| `--scenario` | `wohlhabender-hof` | `leerer-hof`, `verschuldeter-hof`, `wohlhabender-hof`, `voller-silobestand`, `leasing-hof`, `knappe-kasse`, `konflikt-mods`, `helfer-hof`, `tierhof-krank`, `ernte-herbst`, `nachbarhandel`, `duerre-sommer` |
| `--interval` | `5000` | Real-time ms between cycles |
| `--game-minutes-per-tick` | `60` | Game time advanced per cycle |
| `--savegame-id` | `map_erlengrund_sim_<scenario>` | Simulated savegame id |
| `--seed` | `42` | Seed for the deterministic drift of balance/prices/wear |
| `--control-port` | `8099` | HTTP control API (`0` disables) |
| `--once` | – | Export once, process instructions once, exit |
| `--days-per-period` | `1` | FS25 "days per period" of the simulated calendar (exported as `calendar`) |
| `--reset` | – | Forget previous simulator state (processed instructions, balances) |

Each cycle: game time advances, balance/prices/vehicle wear drift slightly, `instructions.json` is applied,
`instructions_ack.json` and `farm_facts.json` are written. `market_context.json` is written on start, after
every applied `FARMLAND_TRANSFER` and on every cycle in which its content changed. Like the mod, the simulator
refuses debits the balance does not cover (`FAILED`, `INSUFFICIENT_FUNDS`). Simulator state (the equivalent of the savegame XML) is kept in
`simulator_savegame.json` inside the bridge folder.

## Scenarios

| Scenario | Purpose |
| --- | --- |
| `leerer-hof` | Start without assets: balance 0, no machines, no fields, no silo stock |
| `verschuldeter-hof` | Active vanilla loan (320 000), little equity, tight liquidity |
| `wohlhabender-hof` | High liquidity, large machine park, full silos; booking journal, stable, fields and weather (Hof-Tablet e2e tests and screenshots) |
| `voller-silobestand` | Focus on storage valuation: very full silos with several fill types |
| `leasing-hof` | Part of the machines leased: exported as `liabilities.leasing`, not as assets (TODO T-04) |
| `knappe-kasse` | Nearly empty account: debits fail with `INSUFFICIENT_FUNDS` (TODO T-03) |
| `konflikt-mods` | `FS25_UsedPlus` and `FS25_MarketDynamics` reported in `detectedMods` (TODO T-09) |
| `helfer-hof` | Roadmap V2 (R2-A): `workforce` with one helper driven by employee 1 and one vanilla helper without employee, `finances`, `weather` |
| `tierhof-krank` | Roadmap V2 (R2-A7): `husbandries` with low health, little food and water (pigs without `productivity`), `finances`, `weather` |
| `nachbarhandel` | Roadmap V3 (R3-H, R3-V): `npcFields` on the farmlands without an owner (harvested barley, growing wheat, a stony empty field, ripe canola), `tradeStorage` of the own silos (wheat, barley, straw with free capacity only) and the shop catalog `storeVehicles` in `market_context.json` (one entry without `motorized`) |
| `duerre-sommer` | Roadmap V3 (R3-W): starts in June; no rain for the whole run (`weather`), own crops still growing (`fields`, `fieldRules`) |
| `ernte-herbst` | Roadmap V2 (R2-C): starts in September; `fields` with ready maize, growing potatoes, withered wheat and a weedy empty field (with the crop details `withered`, `cut`, `fillType`, `litersPerSqm`), `fieldRules` with every soil mechanic on, rain in `weather`, `finances` |

All scenarios share the map "Erlengrund" with 16 farmlands; farmland 16 is the village area
(`showOnFarmlandsScreen: false`, TODO T-11). The calendar starts at monotonic day 0 with period 1 (March) of year 1
(`ernte-herbst`: period 7, September; `duerre-sommer`: period 4, June; each on the first simulated day).

**Roadmap V2 blocks** (`finances`, `workforce`, `husbandries`, `fields`, `fieldRules`, `weather`, see
[`docs/dev/bridge-protocol.md`](../../docs/dev/bridge-protocol.md)): only `wohlhabender-hof`, the three Roadmap V2
scenarios and `duerre-sommer` export them.
All other scenarios leave them out and stand for a mod that does not deliver them yet, so the backend must treat a
missing block as "not present". The values are simulated examples, not numbers read from FS25:

- `finances` (R2-B1): the daily income/expense drift, sales (`/sell`) and finished missions are summed per FS25
  period under the scenario's money types; tool bookings land under `RPSIM_<REASON>`. The last 13 periods are kept.
- `workforce` (R2-A4): every helper job with an `employeeId` adds the elapsed game time to `workedGameMs` of that
  employee.
- `fields` (R2-C1): only fields on farmlands the player owns are exported (a `FARMLAND_TRANSFER` changes the list).
  The crops do not grow on their own - change them with `POST /field`. `fieldRules` (R2-C) stands for the soil
  settings of the savegame (`POST /field-rules`).
- Journal, worked time, fields, husbandries, weather and the last `EMPLOYEE_ROSTER` are part of the simulated savegame
  and go back on `/reload-without-saving`.

**Roadmap V2 instructions:** `EMPLOYEE_ROSTER` replaces the stored roster (`GET /state` → `roster`) and acts on the
running helper jobs like the mod (R2-A2 / R2-A5): the job of a `STRIKE` employee is stopped (removed from
`activeJobs`, logged), the job of an employee no longer `ACTIVE` or no longer in the list keeps running as a vanilla
helper (no `employeeId`), and jobs without employee get the first free `ACTIVE` `MACHINE_OPERATOR` in list order (the
backend sends the list sorted by skill). Jobs started via `POST /jobs` without `employeeId` are assigned the same way.
The strict helper limit (R2-A3) and the dropped game wage (R2-A1) are not simulated. `PROMPT` is "shown" once
(`GET /state` → `prompts`; an expired one is acknowledged `APPLIED` / `EXPIRED` without being shown, a promptId already
queued, answered or withdrawn `DUPLICATE`); answer it with `POST /answer` - like the mod, the simulator writes
`export/player_responses.json` at once, removes answers listed in `ackedResponses` and drops questions listed in
`withdrawnPrompts` (R2-F1). `REPAIR_VEHICLE` with `targetDamage` repairs down to that damage and never raises it.

**Roadmap V3 blocks** (`npcFields`, `tradeStorage` in `farm_facts.json`, `storeVehicles` in `market_context.json`):
only `nachbarhandel` exports them (plus `missionLimitReached: false`, R3-H5). `npcFields` lists only farmlands no farm owns (a `FARMLAND_TRANSFER` changes the
list); `tradeStorage` = own silos per fill type with `freeCapacity = capacity - amount`. Silo contents and contracts
created by `MISSION_CREATE` are part of the simulated savegame and go back on `/reload-without-saving`.

**Roadmap V3 instructions** (executed like the planned mod actions, R3-Q2):

- `STORAGE_TRANSFER` `IN` / `OUT` changes `tradeStorage` and `assets.storage`; `FAILED` with `NO_CAPACITY` (also for a
  fill type no own silo accepts) or `INSUFFICIENT_STOCK`, the rest of the batch (its `MONEY_TRANSACTION`) is aborted.
- `MISSION_CREATE` adds an `AVAILABLE` contract (`missions[]`, client = the farmland's NPC) on an exported neighbour
  field, ack `result.missionId`; `NOT_AVAILABLE` when the farmland has an owner, no neighbour field or an open contract.
  The "player" finishes it with `POST /mission` like a vanilla contract.
- `VEHICLE_SPAWN` adds an own vehicle (value = price, damage from the instruction) and books `-price` under
  `moneyReason` itself, ack `result.vehicleId`; `FAILED` with `UNKNOWN_STORE_ITEM` (not in `storeVehicles`) or
  `INSUFFICIENT_FUNDS`. Free shop places are not simulated.
- `VEHICLE_REMOVE` removes an own vehicle; `VEHICLE_NOT_FOUND`, `NOT_OWN_VEHICLE` for a leased one.

## Control API (manual testing / E2E)

| Request | Effect |
| --- | --- |
| `GET /state` | Current simulator state |
| `POST /advance {"hours": 48}` or `{"days": 2}` | Fast-forward game time (in 24 h steps, each step processed + exported) |
| `POST /tick {"gameMinutes": 0}` | Run one cycle immediately |
| `POST /sell {"sellPoint":"MillNorth","fillType":"WHEAT","liters":8000}` | Simulate a sale (fixed-contract tracking) |
| `POST /balance {"balance": 50000}` | Force the bank balance |
| `POST /days-per-period {"daysPerPeriod": 3}` | The player changes "days per period" in FS25 (the current period keeps its start day) |
| `POST /save` | "Save the game" in FS25 (snapshot of the game state incl. the mod's processed list) |
| `POST /reload-without-saving` | Quit without saving and load the last save: game time, money and processed instructions go back (TODO T-02) |
| `POST /mission {"uniqueId":"mission_001","status":"FINISHED","success":true}` | The player takes / finishes a vanilla contract (TODO T-22) |
| `POST /book {"moneyType":"SHOP_PROPERTY_BUY","amount":-90000}` | A booking of the game (R2-B1): changes the balance and lands in `finances` under that FS25 money type (scenarios with a journal only) |
| `POST /weather {"raining":true,"rainFallScale":0.8}` | Change the exported weather (Roadmap V2 scenarios only) |
| `POST /husbandry {"husbandryUniqueId":"hus_00001","health":80,"food":0.6}` | Change the values of a husbandry (`tierhof-krank`) |
| `POST /field {"farmlandId":7,"weedState":0}` | Change the state of a field (`ernte-herbst`), e.g. `{"farmlandId":2,"growthState":9,"cut":true}` = harvested |
| `POST /field-rules {"limeRequired":false}` | The player changes the soil settings of the savegame (`ernte-herbst`) |
| `POST /npc-field {"farmlandId":3,"growthState":10,"cut":true}` | Roadmap V3 R3-H1: change a neighbour field (`nachbarhandel`), e.g. harvest it (`HARVESTABLE` → `HARVESTED` fills the neighbour's stock) or `{"farmlandId":3,"stoneLevel":2}`; `null` removes a value |
| `POST /mission-limit {"reached": true}` | Roadmap V3 R3-H5: the player farm reaches the game's contract limit (`missionLimitReached`) or not |
| `POST /vanilla-loan {"change": 30000}` | Roadmap V2 R2-D1: the player takes (positive) or repays (negative) the vanilla loan in the finance menu; the balance moves by the same amount |
| `POST /vanilla-farmland {"farmlandId": 13, "toPlayer": true}` | Roadmap V2 R2-D2: the player buys (`true`) or sells a farmland in the game's field menu at its price (booked as `FIELD_BUY` / `FIELD_SELL`), market context re-exported |
| `POST /answer {"promptId":"prm_…","answer":"YES"}` | Roadmap V2 R2-F: the player answers a yes/no question in the game (`YES` / `NO`); 400 for an unknown question |
| `POST /jobs {"activeJobs":[{"jobId":5,"employeeId":2,"title":"John Deere 8R"}]}` | Replace the running helper jobs (`helfer-hof`); jobs without `employeeId` get a free operator of the last roster; optional `categories` (FS25 shop categories of the vehicle, e.g. `["HARVESTERS"]`) limit that to operators with the matching training ("Schulungen") |

## Running the whole tool without FS25

1. Start the simulator: `node src/cli.js --scenario wohlhabender-hof`
2. Start the backend with the `dev` profile (its bridge path defaults to
   `tools/bridge-simulator/runtime/modSettings/FS25_RPSim`): `cd backend && mvn spring-boot:run`
3. Start the frontend: `cd frontend && npm start` and open http://localhost:4200
4. Go through the onboarding; in step 5 the simulated savegame (`map_erlengrund_sim_…`) appears for linking.
5. Use `curl -X POST localhost:8099/advance -d '{"days":2}'` to let credit decisions, salaries etc. happen.

## Tests

```bash
npm test
```
