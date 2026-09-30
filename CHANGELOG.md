# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning:** mod, backend and frontend share one project version (`MAJOR.MINOR.PATCH`). The FS25 mod uses the
four-part form `MAJOR.MINOR.PATCH.0` in `modDesc.xml`. `tools/release/build-release.sh` refuses to build when the
versions or this changelog do not match.

## [Unreleased]

Update the mod `FS25_RPSim` together with the backend: an older mod rejects the booking reason `TRAINING` (the training
is cancelled again) and lets every machine operator drive every vehicle.

### Added

- **Roadmap V3 (`docs/architecture/ROADMAP_V3.md`):** plan for the next features, each checked against the FS25 code -
  tablet access inside the home network (PIN, address, home-screen icon), trade with neighbours from and into own silos
  for every fill type with a silo, real field contracts created by neighbours, leasing out own fields, loan collateral,
  12-month liquidity plan and annual report, price alerts, forward contracts, farm shop, drought with aid and index
  insurance, used machines bought from / sold to NPCs, office clerk and apprentice, milestones and a chronicle export.
  Open decisions are listed in `QUESTIONS.md`.
- **Roadmap V3.1 (`docs/architecture/ROADMAP_V3.1.md`):** addition to V3, checked against the FS25 code - contractor
  field work on own fields (field finish task like a completed contract, harvest into the silo), borrowed and demo
  machines, livestock trade with neighbours, winter service on snow days, seasonal workers, area payment application,
  investment grant, fertiliser rules (closed period, slurry store), animal disease zones, agricultural social insurance
  with sick leave, village newspaper, village group chat, regulars' table, complaints about night work and crop damage,
  farm holidays and school visits, cooperative shares, diesel theft and a farm map with the real field shapes.
- **Trainings for machine operators ("Schulungen"):** without a training a machine operator drives small and medium
  tractors as FS25 helper; large tractors, combines, forage harvesters, special harvesters, trucks and self-propelled
  machines / loaders need the matching training (FS25 shop category of the driven vehicle, configurable in
  `rpsim.formulas.training.categories`). The mod assigns a helper only to an operator with the training (the one with
  the fewest trainings first); in the strict helper limit the start is refused with a message, otherwise the vanilla
  helper drives. *Personal* → *Schulung* books one (`POST /api/employees/{id}/training`, catalog `GET /api/trainings`):
  money reason `TRAINING` (4,500–12,000 €), one game day away (`ON_LEAVE` for the mod), +appreciation, thank-you mail and
  diary entry. Machine operator applicants bring a training along with 30 % and expect 8 % more salary. Employees hired
  before have no training.

### Fixed

- **Mod: missing booking titles in the money popup** (`Missing 'rpsim_money_TRAINING' in l10n_de.xml`): FS25 loads
  the texts of `modDesc.xml` only into the i18n of the mod environment, while the HUD looks booking titles up in the
  global `g_i18n`. The mod now copies its texts into the global text table at load time (existing game texts are never
  overwritten), so all `rpsim_money_*` titles and the own AI messages show their German/English text.

- **Strict helper limit also holds for Courseplay and AutoDrive:** the limit (as many helpers as active machine
  operators) only lowered `maxNumHirables`, which the game checks in its own start menu and key only; mods that start
  helpers their own way could exceed it. The mod now refuses such a start itself (*Kein freier Maschinenführer (strenger
  Modus)*) and stops a helper started over the limit right away with its own message.

## [1.7.0] - 2026-09-30

The web app becomes the **Hof-Tablet**: every function is an app on a tablet home screen. Update the mod
`FS25_RPSim` to 1.7.0.0 together with the backend: without it the status bar shows rain and ground wetness only (no
temperature) and a Sondertilgung is reversed because the older mod rejects its money reasons.

### Added

- **Hof-Tablet shell:** sticky status bar (map, game day and time, FS25 month, weather, balance, live state), start
  screen as home screen (clock, date line with weather, KPIs *Monatsergebnis* / *Nächste Abbuchung* / *Ansehen*, app
  grid with badges, widgets *Zu erledigen*, *Felder*, *Stall*), app header with *Start*, dock with Post, Telefon,
  Aufgaben and Kalender (floating on the start screen, bottom bar in every app). The tablet always fills the browser
  window. The icon rail, the bell and the *Letzte Ereignisse* feed are gone.
- **App Aufgaben** (`/aufgaben`, `GET /api/tasks`): every open decision of all areas in one list, sorted by
  deadline and grouped into today / this week / later, with filters (today, money, farm, village), the app of each
  entry, the notices from the game and the number of questions waiting in the game.
- **App Kalender** (`/kalender`, `GET /api/calendar`): agenda of the next game days, the debits of the next month
  start with total and estimated balance, bills to pay yourself, the fixed dates of the FS25 year (festivals, tax
  dates, rotation check, family occasions) and the festival invitations.
- **App Stall** (`/stall`, `GET /api/stables`): the stables as the game reports them (health, food, water,
  productivity, value), animal keepers per animal count, the next vet routine, an announced animal welfare
  inspection and the cases of vet, livestock trader and breeding advice.
- **Apps Versicherung, Werkstatt and Ämter** (`/versicherung`, `/werkstatt`, `/aemter`): the former page *Verträge &
  Vorgänge* is split by topic; the tax card moves from the bank to *Ämter*. Lease, compensation claims and contractor
  referrals live in *Flurkarte*, sponsoring in *Kontakte*, invitations in *Kalender*. `/contracts` only redirects to
  the app of a case or contract.
- **Flurkarte:** table *Meine Felder* (`GET /api/field-overview`) with crop, phase, to-dos and the crop rotation
  against the previous year, plus the expected rotation premium and a warning for repeated crops.
- **Decisions in the mail:** a mail that needs a decision shows the decision card with the same actions as the app
  (no free reply) and *In der App öffnen*; mailbox filter *Entscheidung* replaces *Formular*.
- **Weather with temperature:** the mod exports `weather.temperature` (°C, `getCurrentTemperature`), the backend
  passes the weather to the savegame context, the status bar and the start screen show it. There is no forecast
  (FS25 offers no API for it).
- Bridge simulator: scenario `wohlhabender-hof` carries booking journal, stable, fields, field rules and weather.
- **Sondertilgung** (`POST /api/loans/{id}/sondertilgung`): every running loan (legacy loan included) can be repaid
  early by any amount up to the remaining debt. The installment stays, the term gets shorter (`LoanView` carries
  `remainingInstallments` and the current conditions). Per FS25 year 10 % of the original principal are free of
  charge, above that the bank books 1 % Vorfälligkeitsentschädigung on top (same batch). A full repayment adds the
  pro-rata interest of the running month (counts as deductible interest for the tax office). Refused while an
  installment is overdue, during a deferral or when the liquidity does not cover it. The bank advisor confirms by
  mail (`CREDIT_SPECIAL_REPAYMENT`, plus `CREDIT_PAID_OFF` on full repayment), the diary records it, and from 5 % of
  the remaining debt her trust rises by 3. All values are configurable (`rpsim.formulas.credit.special-repayment-*`).
  New money reasons `CREDIT_SPECIAL_REPAYMENT` and `CREDIT_PREPAYMENT_FEE`: **update the mod `FS25_RPSim`** – an
  older mod rejects them and the backend reverses the Sondertilgung.

### Changed

- Loan plan: the bank shows the installments actually left (computed from the remaining debt) instead of term minus
  paid installments, and the booking note of an installment counts against them (`Kreditrate 5/40`).
- **App names:** Post, Telefon, Kontakte, Agrarbörse, Flurkarte (the paths `/mailbox`, `/calls`, `/village`,
  `/market`, `/farmland` stay). Mail templates and prompts name the app ("in der App „Ämter“"), `formLink`s point to
  the new apps.
- The helper settings move from *Einstellungen* to *Personal*; the surcharge after a loan from the game menu is shown
  on the credit form of the bank.
- Screenshots are renamed after the apps and extended by Aufgaben, Kalender, Versicherung, Ämter, Stall and
  Werkstatt; full-page shots take the status bar and the dock out of the flow.
- **Stock export (`assets.storage`) covers productions and bunker silos:** besides silos, the farm's stock now
  includes silo extensions, the input and output storage of own production points and bunker silos (`CHAFF` while
  filling, `SILAGE` once closed, as the game shows it; capacity 0). Husbandries and halls (pallets/bales) stay out.
  The storage page shows no capacity for entries without one.
- **FarmPulse icon everywhere:** the web app uses the mod icon as favicon (`favicon.ico`, `favicon.svg`,
  `apple-touch-icon.png`); the release bundle ships `farmpulse.ico` and `Desktop-Verknuepfung.bat`, which creates a
  desktop shortcut "FarmPulse" to `start.bat` with that icon (a `.bat` cannot carry an icon itself). `start.bat` sets
  the console title to "FarmPulse". All icons come from `tools/release/make-icons.py` (was `make-mod-icon.py`).

### Fixed

- Bridge simulator: `--reset` cleared its state only after loading the saved one, so the old state survived.
- **Stock export empty in the live test:** only placeables owned by the farm were read, so the farm's storage in
  per-farm silos of the map (the placeable belongs to the map) was missing; ownership is now checked per storage
  (`storage.ownerFarmId`). Silo extensions were not read at all. Stock in productions and bunker silos was not
  exported by design until now (see Changed).
- The first export logs every storage place in `log.txt` (`Stock: …` plus one `Storage …` line each: kind, counted
  or ignored with the reason, fill levels), so a missing stock can be traced in the live test.
- **Booking journal: asset purchases and sales counted as operating business.** The FS25 money types seen in the
  live test were unknown to the backend, so a vehicle or field purchase lowered the monthly result and the cash flow
  of the credit check. `SHOP_VEHICLE_BUY` and `FIELD_BUY` now count as investment, `SHOP_PROPERTY_SELL` and
  `FIELD_SELL` as divestment (`rpsim.formulas.finance.categories`). `OTHER` (*Sonstiges*) stays unclassified and
  counts by its sign. The bridge simulator books farmland-menu trades as `FIELD_BUY` / `FIELD_SELL`.

## [1.5.1] - 2026-09-28

The mod exports every 10 s instead of every 60 s. Update the mod `FS25_RPSim` to 1.5.1.0; the backend needs no
change beyond the version.

### Changed

- **Mod export every 10 s:** `farm_facts.json` (including `market_context.json` when it changed) is now written every
  10 s real time instead of every 60 s (`exportIntervalMs` = 10000), and the fields are sampled for every export
  (`fieldExportIntervalMs` = 10000 instead of 5 min). Both stay configurable in `rpsim_config.xml`.

## [1.5.0] - 2026-09-28

Roadmap V2 (`ROADMAP_V2.md`): real farm finances, staff as FS25 helpers, fields and weather, reactions to the game
menus, new roleplay areas and yes/no decisions directly in the game. Update the mod `FS25_RPSim` to 1.5.0.0 together
with the backend: with an older mod the blocks it does not export stay "not present" and the features built on them
stay off.

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
- **Bypassing the tool in the game menus becomes part of the story (Roadmap V2, R2-D)** - nothing is locked:
  - Vanilla loan: the bank advisor writes within a game day when the loan of the finance menu grows (trust loss
    scaled by the amount, capped); from the second loan while one is open new credits cost an interest surcharge until
    it is repaid; every repayment gets a friendly note. A reload without saving is no repayment.
  - Field menu: a field of a character bought over their head costs trust and village reputation, the former owner
    claims 10 % of the game price (*Verträge* → *Ausgleich zahlen* / *Ablehnen*, no answer = refused); a free field is
    only noted in the diary, an own field sold in the menu is village gossip.
  - Helpers without employee: one hint of the cooperative with a link to *Personal*.
  - Settings card *Kredit und Felder im Spielmenü* (switch, running surcharge); values in
    `rpsim.formulas.vanilla-bypass.*`. Simulator: `POST /vanilla-loan`, `POST /vanilla-farmland`.
- **New roleplay areas (Roadmap V2, R2-E)** - all from the booking journal, the fields and the stables, no new game API:
  - Tax office: the FS25 year is the tax year; at the start of the next year an assessment with the traceable
    calculation (operating income and expenses without taxes and fines, depreciation of vehicles and buildings,
    interest of the bank credits, allowance, flat rate - stricter in the harsh mode), back payment or refund,
    quarterly prepayments from the last assessment. Bills are paid by button under *Verträge*; late fees per started
    month, reminder, enforcement threat (text and trust only). Tax advisor as a contract (monthly fee, lower tax,
    deadline reminders, fewer audits); a random audit disputes expenses under unknown money types and jump months.
    *Bank* → new card *Steuern*.
  - Authority: rotation premium per hectare with a crop change at the end of every FS25 year, a notice for the same crop
    twice, the whole premium cut on a repeat; announced inspections for the cultivation duty (fine) and animal welfare
    (requirement, then fine and village reputation); at most 2 inspections per month.
  - Family: parents, partner and children chosen one by one in the onboarding (reroll keeps role and name);
    retirement payment for an inherited farm or a return home; birthdays, wedding day, school start, help at harvest
    time, succession in the diary; a family field chosen on *Felder* - selling it costs the trust of the whole family.
  - Clubs: shooting club, fire brigade and sports club (role `CLUB`), festival calendar (Maibaum, Schützenfest,
    Feuerwehrfest, Erntedankfest, Weihnachtsmarkt) with invitations to accept or decline (ignoring costs a little
    trust) - replaces the fixed invitation calendar `village-life.invitation-every-periods`; sponsoring requests with
    fixed tiers raise the village reputation.
  - Values in `rpsim.formulas.tax.*`, `authority.*`, `family.*`, `clubs.*` (V20 migration).
- **Decisions directly in the game (Roadmap V2, R2-F)** - simple yes/no decisions without switching to the browser:
  - Mod: `PROMPT` questions are queued and shown one at a time with the game's yes/no dialog as soon as no menu is
    open (`promptsInVehicle` switch); the buttons stay "Ja"/"Nein", their meaning is in the text. The answer is written
    at once to the new file `export/player_responses.json`; queue and open answers are kept in the savegame.
  - Key *FarmPulse: offene Frage* (default Alt+J, rebindable) opens the next waiting question, also in a vehicle; the
    key help shows it while a question waits.
  - Backend: open decisions of the occasions switched on per savegame (default: only calls) become questions -
    calls, contract offers and lease renewals, the hunting tenant's offer, the bank's counter offer, invitations, and
    (owner decision) compensation claims, tax bills and the tax advisor's offer. Answers are processed once per
    `responseId` with the same service methods as the browser buttons; a refused action comes back as a notification.
    `instructions.json` acknowledges answers (`ackedResponses`) and withdraws questions decided in the browser
    (`withdrawnPrompts`); after a reload without saving an open question is sent again.
  - Settings card *Fragen im Spiel*; `rpsim.bridge.ingame-prompts`, `prompt-default-kinds`, `prompt-max-age-hours`
    (V21 migration). Simulator: `POST /answer`, `export/player_responses.json`.

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
