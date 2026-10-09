# Testing

| Part | Command | Runner |
| --- | --- | --- |
| Backend (unit + integration + E2E vs. simulator) | `cd backend && mvn verify` | JUnit 5, Spring Boot Test, JaCoCo |
| Mod (Lua) | `cd mod && lua5.1 tests/run.lua && luacheck .` | own mini runner, luacheck |
| Bridge simulator | `cd tools/bridge-simulator && npm test` | `node --test` |
| Frontend unit | `cd frontend && npm test -- --watch=false` | Vitest (jsdom) |
| Frontend E2E | `cd frontend && npm run e2e` | Playwright vs. backend + simulator |
| Screenshots (on demand) | `cd tools/screenshot-generator && npm run screenshots` | Playwright, writes `docs/screenshots` |

Related: [`manual-test-plan.md`](manual-test-plan.md) (click-through with the simulator scenarios),
[`configuration-reference.md`](configuration-reference.md) (kept complete by `ConfigurationReferenceDocTest`),
[`frontend.md`](frontend.md) (why Vitest + Playwright).

## Backend coverage (AP-9.1)

`mvn verify` writes the JaCoCo report to `backend/target/site/jacoco/index.html` (plus `jacoco.csv`/`jacoco.xml`).
No fixed percentage gate is enforced (by design of the work plan); instead the core formulas are covered completely.

Result of 2026-09-25 (328 tests, all green):

| Scope | Line | Branch |
| --- | --- | --- |
| **Total** | 95.2 % | 75.1 % |
| `CreditFormula` (Bonitäts-Score) | 100 % | 100 % |
| `CreditScoringService` (inputs incl. cash-flow window) | 100 % | 100 % |
| `NegotiationFormula` (Preisfindung, NPC-Gebote) | 100 % | 100 % |
| `SatisfactionFormula` | 100 % | 100 % |
| `VillageReputationService` (Dorf-Ansehen) | 100 % | 100 % |
| `TrustScoreService` | 100 % | 100 % |
| `LoanService` (Zahlungsausfall-Eskalation) | 98.9 % | 90.9 % |
| `FactsService` (Warenbestand-Bewertung) | 97.1 % | 90.0 % |

Remaining uncovered branches are mostly defensive paths (I/O errors of the file bridge, HTTP errors of rarely used
AI providers, null-safety guards) - they are not part of the formula layer.

### Boundary tests per formula (exactly at / just above / just below the threshold)

| Formula (technical concept) | Test |
| --- | --- |
| Bonitäts-Score thresholds 75 / 45 (default) | `CreditFormulaTest.defaultThresholdsAreExact` (74.9 / 75.0 / 75.1, 45.0 / 44.9) |
| Bonitäts-Score HART 85 / 55 | `CreditFormulaTest.hardProfileThresholdsAreExact` |
| Trust bonus cap ±8 / ±5 | `CreditFormulaTest.trustBonusIsCapped` |
| Metric saturation, degenerate inputs, annuity | `CreditFormulaTest.metricsSaturateInsteadOfGrowingForever`, `degenerateInputsAreHandled`, `annuityEdgeCases` |
| Cash-flow trend (moving window, min. history) | `CreditScoringServiceTest` (exactly 1 day counts, 12 h does not, window cut-off) |
| Warenbestand-Bewertung | `StorageValuationTest` |
| Zahlungsausfall-Eskalation 1 / 3 / 5 days, final stage | `LoanLifecycleTest.escalationStagesSwitchExactlyAtTheConfiguredDays` (0.99 / 1.0, 2.99 / 3.0, 4.99 / 5.0), `completeEscalationChainStepByStepEndsWithCallback`, `finalStageCanBlockInsteadOfCallBack` |
| Satisfaction score, multiplier clamp 0.5 / 1.2 | `SatisfactionFormulaTest` |
| Warning threshold "< 30" | `EmployeeSystemTest.warningThresholdIsStrict` (30.0 not low, 29.9 low) |
| Kündigung ≥ 14 / ≥ 30 days | `EmployeeSystemTest.resignationEscalationWarnsAt14AndResignsAt30Days` (13.9 / 14, 29.9 / 30) |
| Dorf-Ansehen tiers ±25, base trust ±15 | `VillageReputationFormulaTest.tierBoundaries`, `baseTrustForNewCharactersIsCapped` |
| Verhandlung: accept ≥ effMin, counter ≥ 0.9 · effMin | `NegotiationFormulaTest.purchaseThresholdsAreExact`, `saleThresholdsMirrorPurchase` |
| NPC bid bands 0.9–1.15 / 0.85–1.0 | `NegotiationFormulaTest.npcMaxBidIsDeterministicAndWithinBand`, `npcCounterOfferWithinBandAndCappedByBuyerLimit` |
| Trust score caps and decay | `TrustScoreServiceTest` |

### The two security principles

1. **Credit:** the trust cap is always smaller than the gap between the result thresholds - trust can tip a
   borderline case but never bridge a gross shortfall. `CreditFormulaTest.trustCapIsSmallerThanThresholdGap` sweeps
   every core score 0–100 with the extreme trust values ±100 for the default *and* the HART profile and asserts
   that no rejection below `counterThreshold - trustCap` ever turns into an approval or counter offer;
   `trustTipsABorderlineCase` shows the intended effect at the border.
2. **Negotiation:** the trust adjustment can never move `minAccept` beyond ±5 %.
   `NegotiationFormulaTest.trustAdjustmentIsCapped` and `trustNeverDistortsBasePriceBeyondCap` check the cap with
   extreme trust values for every character trait.

## Frontend unit tests (AP-9.2)

Every component with logic has a spec next to it (`*.spec.ts`, 115 tests): feature pages are tested against
`HttpTestingController` (requests, bodies, UI states), live updates through the `GameStateStore` version signals,
the SSE client with `src/testing/fake-event-source.ts`, pure helpers (thread grouping, credit state mapping,
negotiation helpers, feed building, chart series) directly.

`npm test -- --watch=false --coverage` (2026-09-25): **94.4 % lines, 84.4 % branches** (`coverage/index.html`).

## Frontend E2E tests (AP-9.2)

`cd frontend && npm run e2e` runs `e2e/core-flows.spec.ts` with Playwright. The config starts three processes:

| Process | Command | Notes |
| --- | --- | --- |
| Bridge simulator | `node tools/bridge-simulator/src/cli.js --scenario wohlhabender-hof --reset --control-port 8099` | bridge folder `frontend/e2e/.runtime/…`, 1 tick/s = 10 game minutes |
| Backend | `java -jar backend/target/rpsim-backend-*.jar --spring.profiles.active=e2e` | in-memory H2, `FAKE` AI provider, no API key; the jar is built if missing |
| Frontend | `ng serve --port 4201` | proxies `/api` to `:8080` |

Covered flows (one serial story, the backend starts empty): complete onboarding incl. reroll and savegame linking ·
answering a mail (incl. the character's reply via SSE) · credit application, fast-forwarding the simulator
(`POST /advance`) until the decision is visible, counter offer acceptance · incoming calls triggered by phone
interviews: accept (soft time window, talk, hang up) and decline · hiring and dismissing an employee · direct
negotiation of an NPC-owned field until the deal, incl. the farmland transfer applied by the simulator · the price
history chart with data.

Requirements: Java 21, Maven, Node ≥ 22.22.3 (24 LTS), a Chromium for Playwright (`npx playwright install chromium`,
or the pre-installed `/opt/pw-browsers/chromium`, or `CHROMIUM_PATH`). Ports 8080, 8099 and 4201 must be free.
Measured runtime: **≈ 29 s** for the 7 flows (plus backend start-up), stable in three consecutive runs.

## Backend end-to-end scenarios (AP-6.4)

`BridgeSimulatorEndToEndTest` drives the complete data flow backend → file bridge → real bridge simulator (Node) →
file bridge → backend, one scenario per core module: onboarding + `STARTING_CAPITAL_ADJUSTMENT`, credit application
→ approval → disbursement, price event (regional), auction + direct negotiation (farmland transfer + re-exported
market context), hiring + resignation escalation over 32 game days, village rotation.

- Requirements: Node.js ≥ 20 and `npm ci` in `tools/bridge-simulator` (otherwise the test is skipped).
- Measured runtime: **≈ 6.4 s** for all six scenarios (4-core container, 2026-09-25).
- The direct-negotiation scenario picks an unclaimed field that is *not* already under a (randomly spawned) auction.

`SimulatorScenariosEndToEndTest` (TODO T-14) runs the robustness scenarios against the real simulator:
`knappe-kasse` (a debit the balance does not cover is acked `FAILED` / `INSUFFICIENT_FUNDS` and raises a
notice in *Aufgaben*), `leasing-hof` (leased vehicles arrive as `liabilities.leasing`, not as assets) and `konflikt-mods`
(`detectedMods` in the header context). "Reload without saving" is covered by `RewindIntegrationTest` (backend)
and by the simulator's own tests (`POST /save`, `POST /reload-without-saving`).
Roadmap V2 (R2-Q2): the same test checks that the optional blocks of `helfer-hof` (`workforce`), `tierhof-krank`
(`husbandries`) and `ernte-herbst` (`fields`, `weather`) reach `FactsService`, and that a scenario without them
(`voller-silobestand`) leaves every block `null` ("not present"). `BridgeValidatorTest` covers missing vs. empty vs.
invalid blocks.
Roadmap V3 (R3-Q2): `SimulatorScenariosEndToEndTest` checks that `npcFields`, `tradeStorage` and `storeVehicles` of
`nachbarhandel` arrive (and stay `null` for `wohlhabender-hof`) and that the `vehicleId` of a `VEHICLE_SPAWN` comes back
as ack `result`; `BridgeSyncIntegrationTest.ackResultIsStoredWithTheInstruction`, `BridgeValidatorTest` (the new
blocks) and `FailedInstructionTest` (notice "Mod aktualisieren") cover the backend side. The mod covers normalisation,
validation, `NOT_SUPPORTED` and `result` in `test_farm_facts.lua`, `test_market_context.lua`, `test_instructions.lua`
and `test_persistence.lua`, the simulator the execution in `test/roadmap-v3.test.js`.
Roadmap V3.1 (R31-Q2): `SimulatorScenariosEndToEndTest` checks that snow height, categories, diesel, time of day and
vehicle positions of `winter-schnee` and spray types and field outlines of `lohnunternehmer` arrive (and stay `null`
for `wohlhabender-hof`) and that the litres of a `VEHICLE_FUEL` come back as ack `result`; `BridgeValidatorTest` (the
new fields and blocks) and `FailedInstructionTest` (notice "Mod aktualisieren" for the three new types) cover the
backend side. The mod covers normalisation, validation and `NOT_SUPPORTED` in `test_farm_facts.lua`,
`test_market_context.lua` and `test_instructions.lua` (every booking reason needs its title in `modDesc.xml`,
`test_game_adapter.lua`), the simulator the scenarios, the execution and the control endpoints in
`test/roadmap-v31.test.js`. Q brings no formula; the formula boundary tests come with the features.
Roadmap V3.2 (R32-Q2): `SimulatorScenariosEndToEndTest` checks that the milk storage of `investor-milch` and the oil
mill / canola silo of `grossauftrag` arrive (and that `viehhandel` has no storage) and that a `HUSBANDRY_TRANSFER` is
applied and lowers the milk; `BridgeValidatorTest` (`husbandries[].storage[]`) and `FailedInstructionTest` (notice
"Mod aktualisieren" for `HUSBANDRY_TRANSFER`) cover the backend side. The mod covers the normalisation, the export
from the game, the execution with its failure codes and the booking back of a partly taken amount in
`test_roadmap_v32.lua`, validation and `NOT_SUPPORTED` in `test_instructions.lua`; the simulator the scenarios, the
execution and the control endpoint in `test/roadmap-v32.test.js`. Q brings no formula; the tables, the values under
`rpsim.formulas.bulk-order.*` / `investor.*` and their boundary tests come with G and I (owner decision 2026-10-08).
Roadmap V3.3 (R33-Q2): `SimulatorScenariosEndToEndTest.roadmapV33FieldsArriveFromTheFieldBookScenario` checks that the
rolling / mulching levels, the harvest counter and the crops of the map of `feldbuch` arrive (and stay `null` for
`lohnunternehmer`); `BridgeValidatorTest` (the new fields, `harvests` and `fruitTypes`) covers the backend side. The
mod covers the normalisation in `test_farm_facts.lua` and `test_market_context.lua` and, in `test_roadmap_v33.lua`,
that the adapter reads none of the values yet (Q fixes the contract only, owner decision 2026-10-08); the simulator the
scenario, the counter with its control endpoint `POST /harvest` and its place in the savegame in
`test/roadmap-v33.test.js`. Q brings no formula, no table and no configuration value; they come with F and E (owner
decision 2026-10-08). In the game: manual test plan section 29.
Roadmap V3.3 R33-F (field book): `FieldBookTest` covers the first entry (levels found count as done), a season from
sowing to harvest with every measure, the harvest year and late litres, mulching in the next season, cuts of a grass
field in one entry, the main crop and "harvest beats no harvest", the product of the counter and a falling counter, a
sold field, corrections and "automatisch", "Ernte eintragen", a closed year with its notice and the reopening, the
rewind and the fallback crops; `ApiIntegrationTest.fieldBookEndpoints` the REST endpoints;
`SimulatorScenariosEndToEndTest.roadmapV33FieldsArriveFromTheFieldBookScenario` the running seasons and the counted
grass of `feldbuch`. Mod: `test_roadmap_v33.lua` (levels, crops of the map once with `needsRolling` and the converter
products, own farmland only, both hooks never counting twice, savegame and export). Frontend:
`features/fieldbook/fieldbook.spec.ts`. In the game: manual test plan rows 29.1–29.10.
Roadmap V3.2 R32-G (bulk orders): `BulkOrderTest` covers the request (sell point of the map without production, amount
range and step, instant price = best price x 1.25, a new buyer per request, the call that becomes a mail), refusals and
the factor, the instant delivery batch and its ack, the refused transfer, the delivery months with the fixed price
(1.05 + 0.01 per month), one fixed price per pair and month (forward contract, bulk order, special offer), the limit,
the liquidity plan, the calendar agenda, the settlement (penalty 25 %, trust, factor) and the moved pending
instructions after a changed calendar (bulk orders and forward contracts; a running month keeps its end);
`ApiIntegrationTest.bulkOrderEndpoints` the REST endpoints. Frontend: `features/trade/bulk-orders.spec.ts`. In the
game: manual test plan rows 28.4–28.8b.
Roadmap V3.2 R32-I (large investors): `InvestorTest` covers the formulas and their boundaries (amount limit, chance,
profit share, payout rate, crop area, litres), the offer (2–3 equal-valued packages, different main considerations,
term from the next FS25 year), the monthly trigger (farm report, loss, payment delay, one offer per year, switch), the
acceptance (capital, discarded packages), an ignored and a declined offer, the bank view (silent partnership raises,
subordinated loan lowers the equity ratio), deliveries of goods, milk and animals without money and their acks (a
re-ack counts once, a refused one not), the staged breach (reminder, made up, compensation, termination, claim paid by
button), the overdue claim (reminder per month, payment delay, no interest), A2 / A3 / A4, R1 / R2, P1 veto and
consent, P2 / P4 requests, P3 holiday flat, P5 public action, the end of term (announcement, buy-back, claim without
money, extension), a refused payment that stays open, tasks, calendar and the reminder a week before.
`RewindIntegrationTest.lostInvestorDeliveriesAreResent` the re-send rule, `ApiIntegrationTest.investorEndpoints` the
REST endpoints and the settings switch. Frontend: `features/bank/investors.spec.ts`. In the game: manual test plan rows
28.9–28.14c.
Roadmap V3 R3-M (market and marketing): `MarketingTest` covers the price alarm (best price, hint, mail, once,
re-activation, cap), the forward contract (fixed price, delivery window, `PRICE_EVENT / FIXED` held back until the
delivery month, one fixed price per pair, penalty and trust on the report, no double handling, liquidity plan) and
the farm shop (order from the own silos at the farm-shop price, delivery batch, reputation, refusal factor, failed
transfer). Frontend: `features/market/marketing.spec.ts`. In the game: manual test plan section 15.
Roadmap V3 R3-P (staff): `StaffTest` covers the clerk's audit factor (with and without a tax advisor), the reminders
once per deadline for tax bills, inspections, forward contracts and lease ends (none for a bill when an advisor runs),
the payment on the deadline day (not overloaded, not without money), apprentice applicants (skill, fixed salary, at
most two, roster role without trainings), the monthly skill gain and the takeover (accept, counter offer from 90 %,
below, no answer). Mod `test_workforce.lua` and simulator `test/instructions.test.js`: apprentices drive after the
operators and never with a training. Frontend: the takeover case in `features/contracts/service-cases.spec.ts`. In the
game: manual test plan section 18.
Roadmap V3.1 R31-A (work on the farm): `ContractorWorkTest` covers the offered works per field phase, prices,
yield factor, silo check, lead time (harvest months, trust), the batch on the work day, the cancel reasons and the
acks; `MachineLoanTest` the rent with trust, the funds check, delivery, daily rent, late days, recall on missed rent,
damage compensation, a vanished machine, the demo with purchase offer and the exclusion from the bank's assets,
the maintenance fee and the sale (depreciation and mechanic use the same `LoanedVehicles` filter); `LivestockTradeTest` the animals per role, stock, prices, offers and requests,
free places, the batch with `ANIMAL_TRANSFER`, acks, failures and gossip; `WinterServiceTest` the offer (category,
snow height, October, renewal), snow days once per day with the hint, the payment and the end; `SeasonalWorkerTest`
the posting window, salary, limit, roster role, no raise / training, the contract end after the last salary and the
return next year. The mod covers `FIELD_WORK`, `ANIMAL_TRANSFER`, the stable subtypes, snow height, category and the
spawn at price 0 in `test_roadmap_v31.lua`, the seasonal worker in `test_workforce.lua`; the simulator the stable
export and the borrowed machine in `test/roadmap-v31.test.js`. Frontend: `contractor-work-card.spec.ts`,
`machine-loans-card.spec.ts`, `borrow-machine.spec.ts`, `animal-trade.spec.ts`. In the game: manual test plan
sections 21 and 22.
Roadmap V3.1 R31-B (authorities and grants): `DirectPaymentTest` covers the form in March (deadline end of May, own
fields, crops), the late cut per day, the lapse after the grace days, the on-site check (deviating crop × 1.5,
rotation repeat × 0.5, announcement) and the payment in December, and the switch; `InvestmentGrantTest` the
application rules, purchases only after the approval (journal rises within the month and across months), the funded
machines, the grant with its cap, the pro-rata repayment after a sale in the binding period, the expiry and the late
fee of an authority bill; `FertilizerRulesTest` the closed period (organic type and rising level, grassland and
mineral fertiliser excluded), warning then fine, the fallback without `sprayType`, the switch, the slurry warning and
the October reminder; `AnimalDiseaseTest` the outbreak (only own animal types, idyllic world mode and switch), the
vet check, the requirement with fine, the trade block, the lifting, the price recovery and the cooldown;
`SickLeaveTest` the BG bill (formula, once a year, paid by button), sickness reported by the office clerk with
`ON_LEAVE`, get-well wishes, the return, accidents only in the driving roles and the risk factor.
`RepositorySmokeTest` saves the new entities. The mod exports the spray type (`test_game_adapter.lua`). Frontend:
`direct-payment-card.spec.ts`, `investment-grant-card.spec.ts`. In the game: manual test plan sections 21 and 23.
Roadmap V3.1 R31-D (village life): `VillageNewspaperTest` covers the issue at the period start (sections in print
order, empty ones left out, the price change of the window, no amounts), the narrated articles (template without AI),
the headline in the diary, once per period and a next issue only with new facts; `VillageChatTest` the groups and
their members, the help request with its link (once per request, the daily limit, the next day), the announcements,
gossip and congratulations, and the player post (tone, capped trust of one member, pacing, the answer job);
`StammtischTest` the interval, attending (trust, rumour bonus used up by one rumour), the loner cap and the tip;
`NightWorkTest` the night window, the friendly and the annoyed complaint, harvest time, daytime and idle helpers, the
switch and the idyllic factor; `CropDamageTest` the row of samples, the hint, the complaint, the growing claim, pay and
refuse, stubble, a running order, a lease and fields without owner; `FarmHolidaySchoolTest` the setup, the factors,
the noise / smell cuts with the review and the school request (holidays, healthy animals, allowance, reputation);
`CooperativeTest` buying within the limit, the notice and repayment, the price index and the cap of the dividend,
the assembly vote, the board election, the rumour and forward-contract perks, the calendar dates and the removal
after two missed meetings; `DieselTheftTest` the target (diesel level, driven, tank lock), the night of
`VEHICLE_FUEL`, the police report and gossip, the insurance module, the retries and the switches.
`NarrationPipelineTest` checks the templates and prompt tasks of the new event types. The mod covers `dayTimeMs`, the
diesel export, the position samples and `VEHICLE_FUEL` in `test_roadmap_v31.lua`; the simulator the new money reasons
in `test/roadmap-v31.test.js`. Frontend: `newspaper.spec.ts`, `chat.spec.ts`, `village-economy-cards.spec.ts`,
`tank-lock-card.spec.ts`, `village-life-cases.spec.ts`. In the game: manual test plan sections 21.6 and 24.
Roadmap V3.1 R31-K (field map): `FieldMapServiceTest` covers the outlines of `market_context.fieldShapes` with the
kind (own, leased, leased out, neighbour with name, free), crop and phase of own fields and the symbols (order,
auction, hints), and the empty map without outlines. The mod reads the outlines once per mission
(`test_roadmap_v31.lua`). Frontend: `field-map.spec.ts` (coordinates, colours, hatching, border, labels, symbols,
selection) and `farmland.spec.ts` (Karte / Tabelle, the field card on a click, the hint without outlines). In the game:
manual test plan rows 21.7 and section 25.
Roadmap V3 R3-L (leasing out own fields): `LeaseOutTest` covers the guide value, the neighbours' bids (capital,
85–100 % of the desired rent, the land agent without interest), the fallback on the field phase (offer and demand),
the bank's consent for a pledged field, the agreement (contract, `FARMLAND_TRANSFER FROM_PLAYER`, `LEASE_INCOME` also
with an empty account), the reconciliation without sale or purchase, the asset value and collateral of a leased-out
field, the family field (trust −5, stays the family field), the renewal offer and renewal, the delayed and the
immediate return, the buy-back in the game menu and a failed transfer. `ApiIntegrationTest` covers `/api/lease-out`,
the lease-out form and the lease consent. Frontend: the form and the bids in `features/farmland/farmland.spec.ts`, the
renewal in `features/contracts/service-cases.spec.ts`, the consent in `features/bank/credit-planning.spec.ts`. In the
game: manual test plan section 20.
Roadmap V3 R3-T (chronicle): `ChronicleTest` covers the milestones from stored data (repaid loan also for an older
savegame, area, record harvest, neighbour trade, a switched-off milestone), the payment-delay year (only a fully
observed year, a delay breaks it, a delay still open at the year change counts for the new year, the salary hook),
the crop-rotation streak of the authority, the file name (farm name, map name, default), the chronicle content and its
Markdown, and that the finance-category labels match the frontend. `ApiIntegrationTest` covers `/api/milestones`,
`/api/settings/farm` and the chronicle download and view. Frontend: `features/diary/diary.spec.ts` (milestone badge,
download, error), `features/diary/chronicle-print.spec.ts`, the milestone widget in `features/home/home.spec.ts`, the
farm name in `features/settings/settings.spec.ts` and the onboarding request. In the game: manual test plan section 19.
Roadmap V3 R3-V (used machines): `VehicleTradeTest` covers the game's used-price formula (exponents, age cap, 3 %
floor, hours for a target factor), the monthly offer within the ranges, workshop markup and neighbour discount, the
agreement with `VEHICLE_SPAWN` (no batch, the mod books), `NO_SPACE` retries up to five and the failed deal, an
unknown shop item, an offer that runs out, the sale to 1–3 neighbours within the 110 % cap with the
`VEHICLE_REMOVE` + `VEHICLE_SALE` batch, gossip, and `VEHICLE_ATTACHED`; `RewindIntegrationTest` the re-sending of both
instructions. The mod tests (`test_game_adapter.lua`, `test_persistence.lua`) cover the catalog export with switch
and cap, vehicle names, the asynchronous spawn (no ack until the callback, used values, booking, failures, not
saved while pending) and the removal checks; the simulator `test/roadmap-v3.test.js` the exported names and
`VEHICLE_ATTACHED`. Frontend: `features/workshop/used-vehicles-card.spec.ts`. In the game: manual test plan section 17.
Roadmap V3 R3-W (drought and weather risk): `DroughtTest` covers the month rating (dry, wet, unknown, outside the
growth months), the series with the warning and the drought offer, the declaration with the regional
`HARVEST_FAILURE` (3 largest crops, busy pairs skipped), gossip and aid case, the recording of growing own fields, the
aid application and expiry, the index insurance (payout, too late, cover suspended, premium following the area,
beside storm/hail) and the deduction on the aid. `SimulatorScenariosEndToEndTest` checks that `duerre-sommer` records
the growing own fields. Frontend: `features/insurance/insurance.spec.ts` and the drought aid in
`features/contracts/service-cases.spec.ts`. In the game: manual test plan section 16.
Roadmap V3 R3-K (credit and financial planning): `CreditPlanningTest` (backend) covers coverage, discount and bonus,
the required Grundschuld with the fields the bank names, sale consent with the repayment in the sale batch and its
reversal, the claim after a menu sale (overdue, credit block, payment), the realisation in the harsh mode, the release
on repayment, the liquidity plan (known postings, tax prepayment month, estimate without double counting, reserve) and
the advisor's warning, the yield recording, the farm report with comparison and the annual review (offer, caps,
serious talk, decline, expiry). Frontend: `features/bank/credit-planning.spec.ts`. In the game: manual test plan
section 14.
Roadmap V3 R3-H (neighbour trade and contracts): `NeighborTradeTest` (backend) covers the neighbour fields and the stock
from a harvest, prices and roles, the purchase on request (H3) with its checks, a refused transfer, the spawner, decline
and expiry, the sale (H4) with the reputation cap, the disappointed neighbour, the contracts (H5: matching field,
`MISSION_CREATE`, evaluation from `farm_facts.missions`, lost after a rewind, refused) and the in-game question. The mod
covers `npcFields`, `tradeStorage`, `STORAGE_TRANSFER` and `MISSION_CREATE` in `test_game_adapter.lua`, the simulator
`missionLimitReached` and the control endpoints in `test/roadmap-v3.test.js`; the frontend `features/trade/trade.spec.ts`
(app „Handel“). In the game: manual test plan section 11.
Roadmap V3 R3-N (tablet in the home network): `NetworkAddressesTest` (loopback / private / public sender addresses),
`LanAccessTest` (MockMvc with `setRemoteAddr`: switch off → 403, internet always 403, without PIN direct access, with
PIN session cookie for API and live updates, game-PC-only settings, PIN format and hash, lock after five wrong PINs,
session end after 30 days / new PIN / switch off); the test profile hashes with 1,000 PBKDF2 iterations to stay fast.
Frontend: `app.spec.ts` (PIN login gate) and `features/lan/lan.spec.ts` (login, card, QR code, interceptor). Access from
a real tablet: manual test plan section 12.
Technical review 10/2026, Phase 0 (security hardening): `DatabaseCredentialsTest` (random password for a new H2 file
database, migration of a database without password, crash between file and database, lost password file, configured
password wins), `DatabasePasswordStartupTest` (full start on a Flyway-migrated database of 1.7.0 without password),
`OwnerOnlyFilesTest` (POSIX `600` on the real file system and in Jimfs, Windows-style ACL with the owner alone in Jimfs),
`AllowedHostsTest` and `HostHeaderFilterTest` (DNS rebinding: foreign `Host` → 403 `HOST_FORBIDDEN`, foreign `Origin` of
writing requests → 403 `ORIGIN_FORBIDDEN`), `AiSettingsServiceTest` (a new `baseUrl` host without a new key discards
the key, owner-only file), `LanAccessTest.aiSettingsAreReadOnlyOnATablet`, `ProfileHardeningTest` (no `AUTO_SERVER`,
password file in `dev`/`prod`, no OpenAPI / Swagger UI in `prod`); frontend `features/settings/settings.spec.ts`
(read-only AI form on a tablet). On real Windows: manual test plan section 27.
Technical review 10/2026, Phase 1.1-1.3 (bridge cycle): `CycleResilienceTest` (written before the fix, red on 1.7.0:
a failing day listener is retried and skipped after three attempts while the game goes on, a transient failure is
retried without running any listener twice, a failing ack listener loses neither the ack nor its retry),
`CycleInfrastructureTest` (every listener of every cycle event has its own journal key, every queued payload survives
the database, only own records are deserialised); the bridge-simulator end-to-end tests (`BridgeSimulatorEndToEndTest`,
`SimulatorScenariosEndToEndTest`) drive the rebuilt cycle with the real file protocol - they need
`npm ci` in `tools/bridge-simulator`, otherwise they are skipped.
Technical review 10/2026, Phase 1.4 (optimistic locking): `OptimisticLockingTest` (a stale write fails and the change
saved in between survives - on the code before 1.4 the stale save overwrote it; a version conflict in REST is
`409 CONCURRENT_UPDATE`; a cycle listener that loses a race runs again at once without counting as a failed attempt);
frontend `core/api/concurrent-update.interceptor.spec.ts` (hint + reload on `CONCURRENT_UPDATE` only).
Roadmap V2 R2-B: `FinanceJournalServiceTest` (classes, complete months, window boundaries), `CreditScoringServiceTest`
(journal cash flow ignores investments, real leasing costs), `FinanceNarrationServiceTest` (bank early warning, record
month) and `ApiIntegrationTest.financesFromTheBookingJournal`; the mod covers the journal in `test_finance_journal.lua`,
the frontend the card in `finance-card.spec.ts`.
Roadmap V2 R2-A: `WorkforceServiceTest` (roster order and resend after a rewind, workload from the hours per game
day and from animals per keeper, effect scaling, strike start and end), `MechanicServiceTest` (repair plan, no double
repair after the maintenance contract, no repair on strike), `LivestockStablesTest` (vet emergency with cooldown,
keeper warning, productivity in the breeding advice); the mod covers roster, assignment, wage, limit and worked
time in `test_workforce.lua`, the simulator the roster-driven job assignment and strike stop in `instructions.test.js`,
the frontend the helper settings and the staff hints in `settings.spec.ts` / `employees.spec.ts`.
Roadmap V2 R2-C: `FieldServiceTest` (growth phase, records, crop history, year end, rain hours),
`FieldDamagesAndCreditTest` (hail and wild boars only on standing crops, damage from yield and price, rain factor,
standing crops in the credit check) and `FieldReactionServiceTest` (neighbor, gossip, hints, monthly cap); the mod
covers the field / weather collection in `test_game_adapter.lua` and the normalisation in `test_farm_facts.lua`, the
frontend crop and phase in `farmland.spec.ts` and the hint switch in `settings.spec.ts`.
Roadmap V2 R2-D: `VanillaBypassServiceTest` (vanilla loan taken / repaid, surcharge, reload, switch, field bought over
the owner's head with compensation paid / expired, hint about helpers without employee) and
`NegotiationEngineTest.vanillaPurchaseOfAFreeFieldAndASaleAreFollowedUp`; the frontend covers the claim in
`contracts.spec.ts` and the switch in `settings.spec.ts`, the simulator the game menus in `export.test.js`.
Roadmap V2 R2-E: `TaxServiceTest` (traceable calculation, assessment and prepayments, pay by button with late fees,
reminder and threat, advisor, harsh mode, audit, no journal), `AuthorityServiceTest` (rotation notice and cut,
cultivation duty, animal welfare requirement and fine, monthly cap), `FamilyServiceTest` (family switches, retirement,
occasions, family field sold), `ClubServiceTest` (festival invitation, ignore / decline, sponsoring tiers) and the
festival calendar in `VillageLifeServiceTest`; `ApiIntegrationTest` covers `/api/tax`, the family field, sponsoring
and the family in the onboarding (reroll keeps role and name). The frontend covers the tax card in
`tax-card.spec.ts`, the new cases in `contracts.spec.ts`, the family field in `farmland.spec.ts` and the family
switches in `onboarding-wizard.spec.ts`.
Roadmap V2 R2-F: `PromptServiceTest` (questions asked once, the game's buttons explained, same service methods for the
answers, withdrawal, settings, lease renewal, refused action, bank counter offer, resend after a rewind, older mod,
master switch) and `BridgeSyncIntegrationTest.anIncomingCallIsAcceptedInTheGame` (question out, answer from
`player_responses.json`, acknowledgement out); the mod covers queue, dialog, answers, acknowledgements, withdrawal,
vehicle rule, key and savegame in `test_prompts.lua` and the adapter in `test_game_adapter.lua`, the frontend the
settings card in `settings.spec.ts`, the simulator the answer file in `instructions.test.js`.

## Continuous integration (AP-11.1)

| Workflow | Trigger | Runs |
| --- | --- | --- |
| `.github/workflows/backend.yml` | push to `main` / PR touching `backend/`, the simulator or the config reference | simulator `npm test` (every scenario and instruction against the JSON schemas), `mvn -B verify` (incl. the backend E2E vs. the simulator, JaCoCo artifact) |
| `.github/workflows/frontend.yml` | push / PR touching `frontend/` | `npm ci`, `npm run lint`, `npm run build`, `npm test -- --watch=false` |
| `.github/workflows/mod-lint.yml` | push / PR touching `mod/` | `luacheck .` and the luaunit suite with Lua 5.1 |
| `.github/workflows/e2e.yml` | every push to `main`, PRs touching backend/frontend/simulator, manual | builds the jar, installs Chromium, `npm run e2e`; uploads the Playwright report on failure |

**Decision – E2E on pull requests, too:** the Playwright suite itself takes ≈ 30 s; with the jar build, `npm ci`
and the Chromium download the job takes about 3–4 minutes. That is cheap enough to catch broken flows before they
reach `main`, so it runs on PRs that touch one of the three involved components (docs-only PRs skip it).
Path filters keep unrelated workflows from running at all.
