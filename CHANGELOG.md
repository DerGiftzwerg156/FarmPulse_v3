# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning:** mod, backend and frontend share one project version (`MAJOR.MINOR.PATCH`). The FS25 mod uses the
four-part form `MAJOR.MINOR.PATCH.0` in `modDesc.xml`. `tools/release/build-release.sh` refuses to build when the
versions or this changelog do not match.

## [Unreleased]

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
