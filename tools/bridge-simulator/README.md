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
| `--scenario` | `wohlhabender-hof` | `leerer-hof`, `verschuldeter-hof`, `wohlhabender-hof`, `voller-silobestand`, `leasing-hof`, `knappe-kasse` |
| `--interval` | `5000` | Real-time ms between cycles |
| `--game-minutes-per-tick` | `60` | Game time advanced per cycle |
| `--savegame-id` | `map_erlengrund_sim_<scenario>` | Simulated savegame id |
| `--seed` | `42` | Seed for the deterministic drift of balance/prices/wear |
| `--control-port` | `8099` | HTTP control API (`0` disables) |
| `--once` | – | Export once, process instructions once, exit |
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
| `wohlhabender-hof` | High liquidity, large machine park, full silos |
| `voller-silobestand` | Focus on storage valuation: very full silos with several fill types |
| `leasing-hof` | Part of the machines leased: exported as `liabilities.leasing`, not as assets (TODO T-04) |
| `knappe-kasse` | Nearly empty account: debits fail with `INSUFFICIENT_FUNDS` (TODO T-03) |

## Control API (manual testing / E2E)

| Request | Effect |
| --- | --- |
| `GET /state` | Current simulator state |
| `POST /advance {"hours": 48}` or `{"days": 2}` | Fast-forward game time (in 24 h steps, each step processed + exported) |
| `POST /tick {"gameMinutes": 0}` | Run one cycle immediately |
| `POST /sell {"sellPoint":"MillNorth","fillType":"WHEAT","liters":8000}` | Simulate a sale (fixed-contract tracking) |
| `POST /balance {"balance": 50000}` | Force the bank balance |
| `POST /save` | "Save the game" in FS25 (snapshot of the game state incl. the mod's processed list) |
| `POST /reload-without-saving` | Quit without saving and load the last save: game time, money and processed instructions go back (TODO T-02) |

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
