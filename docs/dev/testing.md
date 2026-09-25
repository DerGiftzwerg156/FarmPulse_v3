# Testing

| Part | Command | Runner |
| --- | --- | --- |
| Backend (unit + integration + E2E vs. simulator) | `cd backend && mvn verify` | JUnit 5, Spring Boot Test, JaCoCo |
| Mod (Lua) | `cd mod && lua5.1 tests/run.lua && luacheck .` | own mini runner, luacheck |
| Bridge simulator | `cd tools/bridge-simulator && npm test` | `node --test` |
| Frontend unit | `cd frontend && npm test -- --watch=false` | Vitest (jsdom) |
| Frontend E2E | `cd frontend && npm run e2e` | Playwright vs. backend + simulator |

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
