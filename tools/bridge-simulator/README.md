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
| `--scenario` | `wohlhabender-hof` | `leerer-hof`, `verschuldeter-hof`, `wohlhabender-hof`, `voller-silobestand`, `leasing-hof`, `knappe-kasse`, `konflikt-mods`, `helfer-hof`, `tierhof-krank`, `ernte-herbst`, `nachbarhandel`, `duerre-sommer`, `winter-schnee`, `lohnunternehmer`, `viehhandel`, `grossauftrag`, `investor-milch`, `feldbuch`, `hofplanung`, `fahrzeughaendler` |
| `--interval` | `5000` | Real-time ms between cycles |
| `--game-minutes-per-tick` | `60` | Game time advanced per cycle |
| `--savegame-id` | `map_erlengrund_sim_<scenario>` | Simulated savegame id |
| `--seed` | `42` | Seed for the deterministic drift of balance/prices/wear |
| `--control-port` | `8099` | HTTP control API (`0` disables) |
| `--once` | – | Export once, process instructions once, exit |
| `--days-per-period` | `1` | FS25 "days per period" of the simulated calendar (exported as `calendar`) |
| `--market-context-interval` | `60000` | Real-time ms after which `market_context.json` is rewritten even when unchanged (the mod's `marketContextIntervalMs`) |
| `--reset` | – | Forget previous simulator state (processed instructions, balances) |

Each cycle: game time advances, balance/prices/vehicle wear drift slightly, `instructions.json` is applied,
`instructions_ack.json` and `farm_facts.json` are written. `market_context.json` is written on start, after
every applied `FARMLAND_TRANSFER`, on the first cycle after every `--market-context-interval` (real time, also when
unchanged) and on every cycle in which its content changed. Like the mod, the simulator
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
| `winter-schnee` | Roadmap V3.1 (R31-A4, D5, D8): starts in December with 0.15 m snow (`weather.snowHeight`); a large and a medium tractor with shop category and diesel (`category`, `fuel`), a trailer without a diesel tank; time of day, empty `vehiclePositions` |
| `lohnunternehmer` | Roadmap V3.1 (R31-A1, A2, B3, K1): starts in August; own fields with ripe wheat, harvested barley, an empty field after slurry and growing canola, each with `sprayType`; own silos with free capacity (`tradeStorage`); the field outlines `fieldShapes` and the shop catalog `storeVehicles` (machines to borrow) in `market_context.json` |
| `viehhandel` | Roadmap V3.1 (R31-A3): a cow stable with free places (Holstein and Angus) and a full sheep stable; `husbandries[]` export the breeds, the possible breeds and the free places (`subTypes`, `supportedSubTypes`, `freeSlots`) from the simulator's model that `ANIMAL_TRANSFER` changes |
| `grossauftrag` | Roadmap V3.2 (R32-G): a full canola silo (`tradeStorage` 600 000 l, no free capacity) and the oil mill "Ölmühle Nord" (`OilMillNorth`, canola, sunflower, soybean) as an extra sell point of the map in `market_context.json` and `prices` |
| `investor-milch` | Roadmap V3.2 (R32-I3, type W3): a cow stable with 12 000 l milk in its storage (capacity 30 000 l); `husbandries[].storage[]` exports it, `HUSBANDRY_TRANSFER` takes it out |
| `hofplanung` | Roadmap V4 (R4-Q): September; wheat sown and fertilised once on field 2, grass after the first cut on field 4, a cow husbandry with 40 cows, a trough with silage and hay, 150 000 l silage and 30 000 l hay in stock, the consumption counter `inputs` (seed, fertiliser bought by a helper, slurry), purchase prices, the food groups of the cows and the crops with seed use, base yield and sowing / harvest windows |
| `fahrzeughaendler` | Roadmap vehicle dealer (RFH-Q, K): the shop catalog with every option in `store_catalog.json` (Fendt 700 Vario with colour, engine (power per option, no type title: the tablet's table applies), wheels and a package; Amazone Catros; Krampe trailer; RFH-K1 display names of brand and category and the power range), two delivery points set by key (`deliveryPoints`), two shop places (`storePlaces`), a Deutz-Fahr to trade in and an attached Catros (trade-in refused) |
| `feldbuch` | Roadmap V3.3 (R33-Q): August; own fields with rolling / mulching levels (harvestable wheat, grass after the first cut, harvestable maize still to be rolled), the crops of the map in `market_context.fruitTypes` (grass grows again, maize also gives `CHAFF`) and the harvest counter `harvests` (18 000 l grass windrow on field 4) |
| `ernte-herbst` | Roadmap V2 (R2-C): starts in September; `fields` with ready maize, growing potatoes, withered wheat and a weedy empty field (with the crop details `withered`, `cut`, `fillType`, `litersPerSqm`), `fieldRules` with every soil mechanic on, rain in `weather`, `finances` |

All scenarios share the map "Erlengrund" with 16 farmlands; farmland 16 is the village area
(`showOnFarmlandsScreen: false`, TODO T-11). The calendar starts at monotonic day 0 with period 1 (March) of year 1
(`ernte-herbst`: period 7, September; `duerre-sommer`: period 4, June; `winter-schnee`: period 10, December;
`lohnunternehmer`: period 6, August; each on the first simulated day).

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
- Roadmap V3.1 R31-A2: `VEHICLE_SPAWN` with `price` 0 brings a borrowed or demo machine without a funds check and
  without a booking (game value = list price; in the V3.1 scenarios with its shop `category`).

**Roadmap V3.1 fields and blocks** (R31-Q2): only `winter-schnee`, `lohnunternehmer` and `viehhandel` (and the V3.2
scenarios `grossauftrag` and `investor-milch`, R32-Q2, the V3.3 scenario `feldbuch`, R33-Q2, and the V4 scenario
`hofplanung`, R4-Q3) stand for a mod with the R31-Q1 contract. They export `calendar.dayTimeMs` (time of day of the game time), `vehiclePositions` (empty
until `POST /vehicle-positions`), the shop `category` and the diesel `fuel` of the own vehicles and
`weather.snowHeight`; `lohnunternehmer`, `feldbuch` and `hofplanung` also the `sprayType` of their fields and `fieldShapes` (15 outlines on a 4 × 4
grid of a 2048 m map). All other scenarios leave them out. Vehicle positions and the stables are part of the simulated
savegame and go back on `/reload-without-saving`. The values, fruit types and animal subtypes are simulated examples.

**Roadmap V3.1 instructions** (executed like the planned mod actions, R31-Q2):

- `FIELD_WORK` sets a simulated end state of an own field: `PLOW` (crop removed, `PLOWED`, plow level 1), `CULTIVATE`
  (crop removed, `CULTIVATED`), `LIME` (lime level 1, `sprayType` `LIME`), `SOW` (new crop, `SOWN`), `HARVEST`
  (`cut`, i.e. `HARVESTED`; the yield goes into the silo with the `STORAGE_TRANSFER` of the batch). `FAILED` with
  `FIELD_NOT_FOUND`, `NOT_OWN_FIELD`, `MISSION_RUNNING` (a `RUNNING` contract on the field) or `UNKNOWN_FRUIT_TYPE`.
- `ANIMAL_TRANSFER` moves animals of one subtype into / out of a stable of `viehhandel` and updates `assets.animals`
  (count, value per animal of the stable); `FAILED` with `NO_ANIMAL_SPACE`, `NOT_ENOUGH_ANIMALS`, `HUSBANDRY_NOT_FOUND`,
  `WRONG_ANIMAL_TYPE` or `UNKNOWN_SUB_TYPE`.
- `VEHICLE_FUEL` takes at most the diesel in the tank, ack `result.liters`; `FAILED` with `VEHICLE_NOT_FOUND`,
  `NOT_OWN_VEHICLE`, `VEHICLE_IN_USE` (the vehicle is in `vehiclePositions`, i.e. driven) or `NO_DIESEL_TANK`.

**Roadmap V3.2** (R32-Q2): only `investor-milch` exports the milk storage of a husbandry (`husbandries[].storage[]`
= `{ fillType, amount, capacity }` in whole litres, entries with `amount + capacity > 0`, sorted by fill type, like
the mod); all other scenarios stand for a mod without it. A scenario may add sell points of its own to the map
(`sellPoints` of the preset; `grossauftrag`: the oil mill). The milk is part of the simulated savegame and goes back on
`/reload-without-saving`. `HUSBANDRY_TRANSFER` is executed like the mod action: the whole amount or nothing out of the
storage of the husbandry; `FAILED` with `HUSBANDRY_NOT_FOUND`, `UNKNOWN_FILLTYPE` (a fill type the simulated game does
not know), `WRONG_FILLTYPE` (no milk sort of the husbandry) or `INSUFFICIENT_STOCK`. The partial take-out with booking
back (a loading station that reaches only part of the storage) is covered by the mod tests only.

**Roadmap V3.3** (R33-Q2): only `feldbuch` exports the rolling and mulching levels of its fields
(`fields[].rollerLevel` / `stubbleShredLevel`, changed with `POST /field`), the crops of the map
(`market_context.fruitTypes`, sorted by name, with `needsRolling` - potatoes are not rolled) and the harvest counter (`harvests` = `{ farmlandId, fruitType, fillType,
liters }`, cumulative whole litres, sorted); all other scenarios stand for a mod without them. `POST /harvest` adds the
litres a harvesting machine got into its tank on an own field, like the planned hook of R33-F3; a contractor harvest
(`FIELD_WORK HARVEST`) is not counted (in the game it is a state jump, not a combine). The counter is part of the
simulated savegame and goes back on `/reload-without-saving`. The values, crops and titles are simulated examples.
`hofplanung` (R4-Q3) has the V3.3 values too.

**Roadmap V4** (R4-Q3): only `hofplanung` exports the consumption counter per own field (`inputs` = `{ farmlandId,
fillType, liters, helperLiters }`, cumulative whole litres, sorted), the feed stock in silos and bunker silos
(`feedStock`), the food values of its husbandry (`foodLitersPerHour`, `foodCapacity`, `trough`, `strawLitersPerHour`,
`straw`, `feedConsumed`) and in `market_context` the purchase prices (`fillTypeCosts`), the food groups per animal type
(`animalFood`, in the order of the game) and the crops with `seedUsagePerSqm`, `litersPerSqm`, `plantablePeriods` and
`harvestablePeriods`; all other scenarios stand for a mod without them. `POST /input` adds litres a sowing machine or
sprayer used on an own field (like the planned hooks of R4-D1), `POST /feed` puts feed into or takes it out of the
stock or a trough, `POST /eat` lets the animals eat from the trough (like the planned hook of R4-F1: the trough falls,
the feed counter grows by what was really taken). Counters, trough and stock are part of the simulated savegame and go
back on `/reload-without-saving`. The values, prices and titles are simulated examples.

**Roadmap vehicle dealer** (RFH-Q5): only `fahrzeughaendler` writes `store_catalog.json` once per start and exports
`deliveryPoints` (farm_facts) and `storePlaces` (market_context); all other scenarios stand for a mod without them.
`VEHICLE_DELIVERY` is executed like the planned mod action of RFH-L5: trade-ins checked first (leased → `TRADE_IN_NOT_OWN`,
missing → `TRADE_IN_NOT_FOUND`, attached → `TRADE_IN_ATTACHED`, in `vehiclePositions` → `TRADE_IN_IN_USE`), funds against
the sum of `bookings` (overdraft `minBalance`), every item from the catalog (`UNKNOWN_STORE_ITEM`), then the delivery
point, the shop places (`fallbackToShop` or no `place`) or `NO_SPACE`; the vehicles are added, the trade-ins removed and
every booking lands single in the booking statement; the ack result is `placeUsed`, `vehicleCount`, `vehicleId1..n`,
`tradeInsRemoved`. `POST /delivery-point` is the key "Lieferort hier festlegen" (own farmland only, the last 20 kept,
part of the simulated savegame), `POST /delivery-place-blocked` and `POST /shop-full` occupy the delivery point or the
shop places. The values and names are simulated examples.

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
| `POST /book {"moneyType":"SHOP_PROPERTY_BUY","amount":-90000}` | A booking of the game (R2-B1): changes the balance and lands in `finances` under that FS25 money type (scenarios with a journal only) and in the booking statement `bookings`. With `"moneyType":"SHOP_VEHICLE_BUY"` and `"vehicleName":"Fendt 942 Vario"` the vehicle is delivered too; with `"moneyType":"SHOP_VEHICLE_SELL"` and `"vehicleId":"veh_00042"` it is removed (vehicle names in the statement) |
| `POST /weather {"raining":true,"rainFallScale":0.8}` | Change the exported weather (Roadmap V2 scenarios only) |
| `POST /husbandry {"husbandryUniqueId":"hus_00001","health":80,"food":0.6}` | Change the values of a husbandry (`tierhof-krank`) |
| `POST /field {"farmlandId":7,"weedState":0}` | Change the state of a field (`ernte-herbst`), e.g. `{"farmlandId":2,"growthState":9,"cut":true}` = harvested |
| `POST /field-rules {"limeRequired":false}` | The player changes the soil settings of the savegame (`ernte-herbst`) |
| `POST /npc-field {"farmlandId":3,"growthState":10,"cut":true}` | Roadmap V3 R3-H1: change a neighbour field (`nachbarhandel`), e.g. harvest it (`HARVESTABLE` → `HARVESTED` fills the neighbour's stock) or `{"farmlandId":3,"stoneLevel":2}`; `null` removes a value |
| `POST /mission-limit {"reached": true}` | Roadmap V3 R3-H5: the player farm reaches the game's contract limit (`missionLimitReached`) or not |
| `POST /snow {"height": 0.4}` | Roadmap V3.1 R31-A4: the snow height of the world changes (metres, `weather.snowHeight`) |
| `POST /vehicle-positions {"positions":[{"uniqueId":"veh_00001","x":-312.5,"z":88,"farmlandId":7,"onCrop":true}]}` | Roadmap V3.1 R31-D5: the own vehicles being driven right now (replaces the last sample; a listed vehicle counts as in use for `VEHICLE_FUEL`) |
| `POST /fuel {"uniqueId":"veh_00002","liters":150}` | Roadmap V3.1 R31-D8: the diesel level of an own vehicle with a diesel tank changes (kept within 0..capacity) |
| `POST /husbandry-milk {"husbandryUniqueId":"hus_00001","fillType":"MILK","amount":20000}` | Roadmap V3.2 R32-Q2: the milk in the storage of a husbandry changes (litres, capped at the capacity; only milk sorts of the husbandry) |
| `POST /harvest {"farmlandId":5,"fruitType":"MAIZE","fillType":"CHAFF","liters":30000}` | Roadmap V3.3 R33-Q2: a harvesting machine gets litres into its tank on an own field (`feldbuch`); the counter of field, crop and product grows (only farmlands the player owns, litres > 0) |
| `POST /input {"farmlandId":2,"fillType":"LIME","liters":4000,"helper":false}` | Roadmap V4 R4-Q3: a sowing machine or sprayer uses litres on an own field (`hofplanung`); `helper: true` = a helper bought them (also counted in `helperLiters`); only farmlands the player owns, litres > 0 |
| `POST /feed {"husbandryUniqueId":"hus_00001","fillType":"SILAGE","amount":5000}` | Roadmap V4 R4-Q3: feed into (amount > 0) or out of (amount < 0) a trough, or without `husbandryUniqueId` the silo / bunker silo stock (`hofplanung`); never below 0, the trough never above its capacity |
| `POST /eat {"husbandryUniqueId":"hus_00001","fillType":"SILAGE","liters":1000}` | Roadmap V4 R4-Q3: the animals eat from the trough; the trough falls and `feedConsumed` grows by what was really taken |
| `POST /delivery-point {"x":-300,"y":99,"z":120,"ry":3.14,"farmlandId":3}` | Roadmap vehicle dealer RFH-Q5: the player presses "Lieferort hier festlegen" at this place (`fahrzeughaendler`); only on own farmland (otherwise 500 with „Lieferort nur auf eigenem oder gepachtetem Land“), the last 20 are kept |
| `POST /delivery-place-blocked {"blocked": true}` | Roadmap vehicle dealer RFH-Q5: the delivery point is taken (`overlapBox` finds something) or free again |
| `POST /shop-full {"full": true}` | Roadmap vehicle dealer RFH-Q5: no shop place is free (`setLoadingPlace` fails) or places are free again |
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
