# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning:** mod, backend and frontend share one project version (`MAJOR.MINOR.PATCH`). The FS25 mod uses the
four-part form `MAJOR.MINOR.PATCH.0` in `modDesc.xml`. `tools/release/build-release.sh` refuses to build when the
versions or this changelog do not match.

## [Unreleased]

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
