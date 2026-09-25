import { existsSync } from 'node:fs';
import { defineConfig, devices } from '@playwright/test';

/**
 * E2E tests of the core flows (AP-9.2) against the real backend (profile `e2e`: in-memory DB, FAKE AI provider)
 * and the bridge simulator - never against a real FS25. All three processes are started here.
 */
// The dev-server proxy (proxy.conf.json) forwards /api to :8080, so the backend port is fixed.
const BACKEND_PORT = 8080;
const SIM_PORT = Number(process.env.RPSIM_E2E_SIM_PORT ?? 8099);
const FRONTEND_PORT = Number(process.env.RPSIM_E2E_FRONTEND_PORT ?? 4201);
const BRIDGE_DIR = 'e2e/.runtime/modSettings/FS25_RPSim';
// Pre-installed Chromium in CI containers; otherwise Playwright's own download is used.
const chromium = process.env.CHROMIUM_PATH ?? (existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: `http://localhost:${FRONTEND_PORT}`,
    locale: 'de-DE',
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
    launchOptions: chromium ? { executablePath: chromium } : {},
  },
  webServer: [
    {
      name: 'bridge-simulator',
      command:
        `node ../tools/bridge-simulator/src/cli.js --dir ${BRIDGE_DIR} --scenario wohlhabender-hof --reset ` +
        `--interval 1000 --game-minutes-per-tick 10 --control-port ${SIM_PORT}`,
      url: `http://localhost:${SIM_PORT}/state`,
      reuseExistingServer: false,
      timeout: 30_000,
    },
    {
      name: 'backend',
      command:
        'cd ../backend && (ls target/rpsim-backend-*.jar >/dev/null 2>&1 || mvn -B -q -DskipTests package) && ' +
        `java -jar $(ls -t target/rpsim-backend-*.jar | head -1) --spring.profiles.active=e2e --server.port=${BACKEND_PORT} ` +
        `--rpsim.bridge.path=../frontend/${BRIDGE_DIR}`,
      url: `http://localhost:${BACKEND_PORT}/actuator/health`,
      reuseExistingServer: false,
      timeout: 240_000,
    },
    {
      name: 'frontend',
      command: `npx ng serve --port ${FRONTEND_PORT}`,
      url: `http://localhost:${FRONTEND_PORT}`,
      reuseExistingServer: false,
      timeout: 180_000,
    },
  ],
});
