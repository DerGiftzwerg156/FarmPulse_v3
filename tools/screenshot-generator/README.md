# Screenshot generator (AP-10.2)

Generates the images in [`docs/screenshots/`](../../docs/screenshots) from a real run of the whole tool - no
mocks: bridge simulator (scenario `wohlhabender-hof`) + backend (profile `e2e`, in-memory database, AI provider
`NONE`, i.e. the German fallback templates write the texts) + Angular dev server. The script plays a short story
(onboarding, credit applications, a job posting and a hire, a land negotiation, a diary note, a few game days) and
captures every feature page.

```bash
cd tools/screenshot-generator
npm ci
npm run screenshots          # ≈ 1 min plus backend start; overwrites docs/screenshots/*.png
```

Requirements: Java 21 + Maven (the backend jar is built if missing), Node ≥ 22.22.3, the frontend dependencies
(`cd frontend && npm ci`) and a Chromium for Playwright (`npx playwright install chromium`, or set `CHROMIUM_PATH`).
Ports: 8080 (backend, fixed by the frontend proxy), 8098 (simulator control API) and 4202 (frontend) must be free;
override the latter two with `RPSIM_SHOTS_SIM_PORT` / `RPSIM_SHOTS_FRONTEND_PORT`.

The run is deterministic enough for documentation (seeded simulator, fixed scenario) but not pixel-identical
(names and texts depend on generation seeds), so it is run on demand, not on every push. In CI it can run in the
`e2e` job after the Playwright tests (same tool chain).

| File | Page |
| --- | --- |
| `00-willkommen.png` | Dashboard without a savegame |
| `01`–`05-onboarding-*.png` | Onboarding wizard, all five steps |
| `10-dashboard.png` | Home dashboard |
| `11-postfach.png` | Mailbox with an open thread |
| `12-bank.png` | Bank & credit |
| `13-personal.png` | Staff, job posting with applicants |
| `14-felder-verhandlung.png` | Field map and negotiation |
| `15-warenbestand-preise.png` | Silo stock, prices, price history chart |
| `16-dorf-charakter.png` | Village with character detail |
| `17-tagebuch.png` | Diary |
| `18-einstellungen.png` | Settings |
| `19-anruf-eingehend.png` | Incoming call overlay |
| `20-anruf-gespraech.png` | Call conversation |
| `21-mobil-dashboard.png` | Dashboard on a phone |
