# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning:** mod, backend and frontend share one project version (`MAJOR.MINOR.PATCH`). The FS25 mod uses the
four-part form `MAJOR.MINOR.PATCH.0` in `modDesc.xml`. `tools/release/build-release.sh` refuses to build when the
versions or this changelog do not match.

## [Unreleased]

### Added

- **Roadmap V2 (`ROADMAP_V2.md`):** plan for the next features, each checked against the FS25 code - staff as
  FS25 helpers, real farm finances from the game's bookings, field/crop/weather export, reactions to vanilla loan
  and field purchases, tax office / authority / family / clubs, yes/no decisions inside the game.
- **Roadmap V2 groundwork (R2-Q):** the bridge contract for the next features, without game-visible changes yet.
  - `farm_facts.json` knows five optional blocks - `finances` (booking journal), `workforce` (helper jobs and
    worked time), `husbandries` (health, productivity, food, conditions), `fields` (crop and field state) and
    `weather`. `schemaVersion` stays `1`; a missing block means "not present" (older mod), not "empty". The mod
    normalises the blocks once a feature collects them; `BridgeDtos` / `BridgeValidator` read and check them.
  - New instruction types `EMPLOYEE_ROSTER` and `PROMPT` are validated by the mod (executed with R2-A0 / R2-F2,
    until then acknowledged `FAILED` / `NOT_SUPPORTED`; `EMPLOYEE_ROSTER` is executed since R2-A); `REPAIR_VEHICLE` takes an optional `targetDamage` for
    partial repairs and never raises the damage.
  - New booking reasons `TAX_PAYMENT`, `TAX_REFUND`, `FINE`, `FAMILY`, `SPONSORING`, `COMPENSATION` in mod, backend,
    booking titles (`modDesc.xml`) and the German UI labels.
  - Bridge simulator: scenarios `helfer-hof`, `tierhof-krank` and `ernte-herbst` export the new blocks (journal and
    worked time grow with the game time), understand the new instructions and offer the control endpoints
    `/weather`, `/husbandry`, `/field`, `/jobs`. The simulator tests (JSON schema validation) now run in the backend
    CI workflow.
  - Docs: every new field with its source in the FS25 code (`docs/dev/bridge-protocol.md`) and section 10 of the
    manual test plan with one check per "Im Spiel prüfen" point of the roadmap.
- **Real farm finances (Roadmap V2, R2-B):**
  - Mod: booking journal - a hook on `Farm.changeBalance` sums every booking of the player farm per FS25 month and
    money type (tool bookings as `RPSIM_<REASON>`), keeps the last `financeJournalPeriods` (13) months in the savegame
    and exports them as `farm_facts.finances`.
  - Credit check: with a journal the operating cash flow is the average of the complete months in the cash-flow
    window; investments, financing and one-off damage bookings do not count, and the real `LEASING_COSTS` replace the
    leasing estimate (T-04). Classes per category in `rpsim.formulas.finance.categories`; unknown categories count as
    operating by their sign and are logged once. Only money type names evidenced in the FS25 code are classified -
    a vehicle purchase counts as operating until its name is checked in the game (manual test plan 10.9).
  - Bank page: new card *Hofbuchhaltung* - income and expenses of the running business per month as stacked columns
    by category with the monthly result, a table with investments, divestments and financing, German category names.
  - Characters: the bank warns (once per loss streak, only with a running bank loan) after two months with a negative
    operating result; the cooperative congratulates on a record harvest revenue month (small trust bonus); credit
    decisions and both messages get the real figures of the last month as narration facts.
  - Simulator: `POST /book` books a game money type (e.g. a purchase) into the journal.
- **Staff as FS25 helpers (Roadmap V2, R2-A):**
  - Mod: executes `EMPLOYEE_ROSTER` - a helper started by the player farm is driven by the first free active machine
    operator (list sorted by skill), the game messages show the employee's name, and with the helper wage mode
    `EMPLOYEES` (default) the game books no helper wage for it (`AIJob.getPricePerMs` = 0). A strict mode limits
    `maxNumHirables` to the active machine operators. The worked game time per employee is stored in the savegame
    and exported as `farm_facts.workforce`; the stable state (health, productivity, food, conditions) as
    `farm_facts.husbandries`.
  - Workload: the backend counts the driven hours per game day - above 8 h (`workload.target-hours-per-day`) the
    workload need drops, below it recovers; the positive monthly effect of an operator scales with the hours of the
    month. Without the block (older mod) the V1 workload decay stays.
  - Strike: after 21 days below the satisfaction threshold an employee goes on strike (badge *Streikt*, mail); the
    mod stops the running helper with "%s legt die Arbeit nieder", the salary keeps running, no positive effect. The
    strike ends when the satisfaction is back at the threshold.
  - Mechanic: repairs the most worn machines at every month start as far as the monthly capacity reaches (partial
    repair via `targetDamage`), sends a workshop report; machines left broken raise the workload.
  - Animal keeper: workload from animals per keeper, working conditions from the stable health, a warning mail when
    food or water run low; the vet comes to an emergency (more expensive) when a stable's health drops below 40 %;
    the breeding advice names the productivity.
  - Settings: new card *Helfer im Spiel* (helper wage via salary, strict mode); *Personal* shows the helper hint, the
    strike badge and the driven hours per month. New settings are documented in `configuration-reference.md`.
  - Simulator: `EMPLOYEE_ROSTER` assigns running jobs in list order and stops the jobs of striking employees.
- **Fields, crops and weather (Roadmap V2, R2-C):**
  - Mod: exports the own fields (`farm_facts.fields`: crop, growth, weeds, stones, lime, plowing, plus `withered`,
    `cut`, `fillType` and `litersPerSqm` from the game), the soil settings of the savegame (`fieldRules`) and the
    weather. The fields are sampled every `fieldExportIntervalMs` (5 min) and after a farmland transfer.
  - Backend: growth phase per field (empty, growing, harvestable, harvested, withered), crop history per FS25 year,
    rain hours per game month.
  - Hail and wild boars only hit standing crops (wild boars only maize, wheat, barley, oat, potatoes); the hail damage
    follows area × yield × current price, the wildlife damage the growth; a rainy month raises the hail probability.
    The messages name field and crop. Without the field export (older mod) V1 stays.
  - Village: a neighbor minds weeds or stones (friendly, later annoyed with a small trust loss), gossip about fallow
    fields and withered crops, the cooperative congratulates when every harvestable field of a year was harvested in
    time; at most 2 field messages per game month.
  - The cooperative gives at most one field work hint per week (harvest ready, lime, plowing - lime / plowing only when
    the savegame requires them); switchable on the new settings card *Felder*.
  - Bank: standing crops count as asset in the credit check (harvest value × growth × `standing-crop-discount` 0.5),
    the advisor mentions them.
  - *Felder*: the detail of an own field shows crop and phase.
  - Simulator: `ernte-herbst` exports the crop details and `fieldRules`, new endpoint `POST /field-rules`.

## [1.1.2] - 2026-09-28

### Fixed

- **`application-local.yml` did not override profile values (backend):** the file was imported from
  `application.yml`, and Spring ranks such an import below `application-<profile>.yml`. With the `prod` profile of
  `start.bat`/`start.sh`, `rpsim.bridge.path` (and `server.address`, the datasource) from `application-prod.yml`
  therefore always won - a custom bridge path, e.g. for a Documents folder in OneDrive, was ignored and the backend
  kept looking in `<user>\Documents\...`. The import now lives in `application-dev.yml` and `application-prod.yml`,
  so every value in `application-local.yml` wins over the profile defaults.

## [1.1.1] - 2026-09-26

### Fixed

- **Savegame loading hung (mod):** FS25 blocks `io.open` in read mode ("io.open, only write mode ('w') is allowed")
  and hands out an object without `read`; reading `rpsim_config.json` in `loadMap` raised, the engine aborted its
  load callback and the loading screen stopped. The mod now reads only XML files with the engine XML API:
  `import/instructions.xml` (written by the backend next to `instructions.json`) and `rpsim_config.xml`
  (replaces `rpsim_config.json`), both wrapping the JSON text in `<rpsim><json>...</json></rpsim>`. Plain reads no
  longer raise, and an error in `loadMap` is logged and leaves the mod inactive instead of blocking the load.

## [1.1.0] - 2026-09-26

Result of the FS25 compatibility analysis (`TODO.md`): fixes for the real game and the first P3 features.

### Fixed

- **Mod start (T-01):** first export in `Mission00.onStartMission` (fallback after `startFallbackMs`), log line with
  the counts of the first export; `market_context.json` is refreshed whenever its content changes.
- **Refused debits (T-03):** the mod refuses a batch whose net debit exceeds the balance (`FAILED` /
  `INSUFFICIENT_FUNDS`); the backend treats it as a missed payment (loan, salary, contract) or a failed deal.
- **Leasing (T-04):** leased vehicles are no assets; they are exported as `liabilities.leasing` and count as an
  obligation in the credit check.
- **Fixed-price contracts (T-05):** sales are counted after pricing (`overwrittenFunction`), so the delivery that fills
  a contract is still paid at the contract price.
- **modSettings path (T-06):** built from `getUserProfileAppPath()`; the bridge folder is logged.
- **File writing (T-07):** new default `atomicWriteMode = "direct"` (the FS25 sandbox has no `os` module).

### Added

- **Reload without saving (T-02):** the backend detects the game-time rewind and re-sends lost bookings
  automatically up to a threshold, deeper rewinds ask the player (dashboard notice).
- **Game month = FS25 period (T-08):** calendar export, all monthly dates follow the periods and "days per period";
  `rpsim.time.*` removed.
- **Conflict mods (T-09), visible sell points and price trend (T-10), non-tradeable farmlands (T-11).**
- **New characters (T-20):** insurance agent (storm/hail insurance, damage reports), hunter (wildlife damage,
  compensation negotiation, joint measures), vet / livestock trader / breeding advisor (only with animals), energy
  supplier (fixed-price contracts and price swings at biogas sell points). New page "Verträge & Vorgänge".
- **Game integration (T-21):** the FS25 NPCs of the farmlands own the NPC fields, in-game notifications for new mails
  and calls (`NOTIFICATION`), own booking titles (`MoneyType.register`), real month and season in the texts.
- **Contract types (T-22):** lease of NPC fields (automatic return with warning, renewal or purchase), maintenance
  contract with in-game repairs (`REPAIR_VEHICLE`), delivery contracts with production points, vanilla contracts
  referred by the contractor.
- **Docs and tests (T-12 – T-14):** open technical points updated, checklist for the first test in the real FS25
  (`docs/dev/manual-test-plan.md`, section 8), new simulator scenarios (`leasing-hof`, `knappe-kasse`,
  `konflikt-mods`, reload without saving).
- **Release workflow:** `.github/workflows/release.yml` builds the player bundle on a pushed tag `v<version>` and
  publishes `FarmPulse-<version>.zip` and `FS25_RPSim.zip` as GitHub Release (notes from this changelog); a manual
  run only builds and keeps the ZIPs as workflow artifact.

### Removed

- Multiplayer / dedicated server is not a goal (removed from `TODO.md`).

## [1.0.0] - 2026-09-25

First complete version of the V1 scope of the functional and technical concept.

### Added

- **Phase 0 – Repository:** monorepo (`mod/`, `backend/`, `frontend/`, `tools/`, `docs/`), MIT license,
  contributing guide, issue/PR templates, `.gitignore` for secrets (`*.local.yml`, `.env`, local AI config).
- **Phase 1 – FS25 mod `FS25_RPSim`:** file bridge with atomic writes (rename or marker fallback) and `savegameId`
  guard; export of `farm_facts.json` (balance, vehicles, buildings, fields, animals, classic silos, vanilla loan,
  prices) and `market_context.json`; import of `MONEY_TRANSACTION`, `PRICE_EVENT` (multiplier ramp/hold/decay and
  fixed-price contracts with quantity tracking) and `FARMLAND_TRANSFER` with batches, acks and idempotency persisted
  in the savegame; luaunit suite and luacheck.
- **Phase 2 – Bridge simulator:** Node tool that plays the mod on the same files, four scenarios, JSON-schema
  validation, HTTP control API (advance time, sell, set balance).
- **Phase 3 – Backend foundation:** Spring Boot 4 / Java 21, H2 + Flyway, 21 entities, bridge sync with game clock
  (day/month events), outbox with batches, facts snapshots.
- **Phase 4 – Fact layer:** trust score, credit scoring (pro-forma equity, cash-flow window, storage valuation,
  HART profile), loans with escalation and deferral, market event engine (bands, rumours, special contracts,
  subsidies), negotiation engine (auctions, direct negotiation, sale offers), satisfaction, hiring and payroll,
  character generator, village reputation, rotation, mandatory-role absence, village life, tone classifier,
  onboarding with story hooks and free-text moderation. All values configurable in `rpsim.formulas.*`.
- **Phase 5 – AI layer:** narration jobs and worker, prompt builder (four blocks, player text as data), fallback
  templates for every event type, memory condensation, providers OpenAI, Anthropic (official SDK), Gemini, Ollama,
  NONE and FAKE, local-only key storage.
- **Phase 6 – API:** REST endpoints for all modules, SSE stream (`mail`, `call`, `diary`, `state`), call state
  machine, end-to-end test against the real simulator.
- **Phase 7 – Frontend foundation:** Angular 22, Tailwind 4 with the design tokens of the design reference, i18n
  (German), shell with icon rail and mobile menu, shared UI library incl. SVG chart, SSE client with reconnect.
- **Phase 8 – Frontend modules:** onboarding wizard, mailbox, calls (overlay + conversation), bank, staff,
  fields & negotiation, storage & prices with price history chart, village & characters, diary, dashboard,
  settings.
- **Phase 9 – Quality:** boundary tests for every core formula, JaCoCo report, 115 frontend unit tests, Playwright
  E2E of the core flows against backend + simulator, manual test plan.
- **Phase 10 – Documentation:** Mermaid architecture diagrams, automated screenshots, German user guide,
  developer docs (setup, complete configuration reference, AI providers, open technical points), README.
- **Phase 11 – Release:** GitHub Actions for backend, frontend, mod and E2E; release build script with backend
  JAR, frontend bundle, mod ZIP (with generated icon) and a ready-to-play bundle; the backend serves the built
  frontend (`rpsim.web.static-dir`) and binds to localhost in the `prod` profile.

### Not in V1 (by concept)

Multiplayer farms, leasing of fields, collateral for large loans, trading goods other than farmland,
languages other than German (prepared, not filled). See `docs/dev/acceptance-checklist.md`.
