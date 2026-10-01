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
Roadmap V3 R3-M (market and marketing): `MarketingTest` covers the price alarm (best price, hint, mail, once,
re-activation, cap), the forward contract (fixed price, delivery window, `PRICE_EVENT / FIXED` held back until the
delivery month, one fixed price per pair, penalty and trust on the report, no double handling, liquidity plan) and
the farm shop (order from the own silos at the farm-shop price, delivery batch, reputation, refusal factor, failed
transfer). Frontend: `features/market/marketing.spec.ts`. In the game: manual test plan section 15.
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
