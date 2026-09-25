# Local development setup

Everything except the Lua mod itself runs without Farming Simulator 25: the bridge simulator plays the mod's part
on the same JSON files.

## Tools

| Tool | Version | Used for |
| --- | --- | --- |
| JDK | **21** (Temurin recommended) | backend |
| Maven | ≥ 3.9 | backend build and tests |
| Node.js | **≥ 22.22.3**, recommended 24 LTS (Angular CLI 22 requirement) | frontend, bridge simulator, Playwright, screenshot generator |
| npm | ≥ 10 | – |
| Lua | **5.1** + `luarocks install luaunit luacheck` | mod tests and lint |
| Chromium for Playwright | `npx playwright install chromium` (or `CHROMIUM_PATH`) | E2E tests, screenshots |
| Farming Simulator 25 (PC) | optional | testing the real mod |

## Repository layout

```
mod/FS25_RPSim/          Lua mod (sensor/actuator, no game logic)      → mod/README.md
backend/                 Spring Boot 4 backend (Java 21, H2, Flyway)
frontend/                Angular 22 frontend (Tailwind 4, Vitest, Playwright)
tools/bridge-simulator/  Node simulator of the mod                     → tools/bridge-simulator/README.md
tools/screenshot-generator/  docs/screenshots generator                → tools/screenshot-generator/README.md
docs/                    concept, architecture, dev docs, user guide
```

## First start (three terminals)

```bash
# 1 – bridge simulator (writes into tools/bridge-simulator/runtime/modSettings/FS25_RPSim)
cd tools/bridge-simulator && npm ci
node src/cli.js --scenario wohlhabender-hof

# 2 – backend (profile dev: H2 file DB in backend/data, bridge path = simulator runtime folder)
cd backend && mvn spring-boot:run
#   → http://localhost:8080/api/..., Swagger UI: http://localhost:8080/swagger-ui.html

# 3 – frontend (dev server with proxy /api → :8080)
cd frontend && npm ci && npm start
#   → http://localhost:4200  (style guide: /dev/style-guide)
```

Then run the onboarding in the browser; step 5 lists the simulated savegame. Fast-forward game time with
`curl -X POST localhost:8099/advance -H 'Content-Type: application/json' -d '{"days":2}'`.
The manual click-through is described in [`manual-test-plan.md`](manual-test-plan.md).

### AI provider during development

Default is `NONE` (German fallback templates). Use `--rpsim.ai.provider=FAKE` for deterministic dummy texts or
configure a real provider on the settings page / in `backend/application-local.yml` (git-ignored) - see
[`ai-providers.md`](ai-providers.md). Never commit a key.

### Profiles

`dev` (default), `prod` (real FS25 folder, `~/.rpsim` database, localhost only), `e2e` (in-memory, FAKE AI),
`test` (unit tests). Details: [`configuration-reference.md`](configuration-reference.md#profiles).

### Against the real game

1. Build the mod ZIP: `tools/release/build-release.sh` (or zip the *content* of `mod/FS25_RPSim`) and copy it to
   `Documents/My Games/FarmingSimulator2025/mods/`.
2. Start the backend with `--spring.profiles.active=prod` (or set `rpsim.bridge.path` to your `modSettings/FS25_RPSim`).
3. Start the frontend as above, or build it and let the backend serve it: `--rpsim.web.static-dir=../frontend/dist/frontend/browser`.

## Checks before every commit

```bash
cd backend && mvn verify                                   # 330+ tests incl. E2E vs. simulator, JaCoCo report
cd frontend && npm test -- --watch=false && npm run lint && npm run build
cd frontend && npm run e2e                                 # Playwright core flows (starts everything itself)
cd mod && lua5.1 tests/run.lua && luacheck .
cd tools/bridge-simulator && npm test
```

See [`testing.md`](testing.md) for what each suite covers and [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the
commit convention.

## Useful paths

| Path | Content |
| --- | --- |
| `backend/data/rpsim-dev.mv.db` | dev database (delete to start over) |
| `backend/data/local-config/ai-provider.properties` | AI settings written by the settings page (git-ignored) |
| `tools/bridge-simulator/runtime/` | simulator bridge folder and state (`--reset` to start over) |
| `backend/target/site/jacoco/index.html` | backend coverage report |
| `frontend/coverage/index.html` | frontend coverage report |

## Release build (AP-11.2)

```bash
tools/release/build-release.sh              # runs the backend tests, ≈ 5 min
tools/release/build-release.sh --skip-tests # when CI is green anyway
```

Produces in `release/` (git-ignored): `rpsim-backend-<v>.jar`, `farmpulse-web-<v>.zip`, `FS25_RPSim.zip` and the
player bundle `FarmPulse-<v>/` + `.zip` (jar, `web/`, mod ZIP, `start.bat`/`start.sh`, German guide, screenshots,
`application-local.yml.example`). The script checks that `pom.xml`, `frontend/package.json`, `modDesc.xml`
(`<v>.0`) and `CHANGELOG.md` agree on the version.

**Decision – the backend serves the frontend:** the start scripts run
`java -jar rpsim-backend.jar --spring.profiles.active=prod --rpsim.web.static-dir=web`; the backend delivers the
built Angular app on `/` (SPA fallback to `index.html`, `/api/**` untouched, `WebConfig`). Players start one
process and open one URL, no Node.js or second server is needed, and there is no CORS setup because UI and API
share the origin. Development keeps the separate `ng serve` with proxy for hot reload.

The mod icon `mod/FS25_RPSim/icon_RPSim.dds` (512×512, BC1) is generated by `tools/release/make-mod-icon.py`.
