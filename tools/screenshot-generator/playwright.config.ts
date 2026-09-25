import { existsSync } from 'node:fs';
import { defineConfig } from '@playwright/test';

/**
 * Screenshot generator (AP-10.2): starts bridge simulator + backend (profile e2e, AI provider NONE so the German
 * fallback templates produce realistic texts) + Angular dev server and captures every feature page.
 * Run: `npm ci && npm run screenshots` - output goes to docs/screenshots/<page>.png.
 */
const SIM_PORT = Number(process.env.RPSIM_SHOTS_SIM_PORT ?? 8098);
const FRONTEND_PORT = Number(process.env.RPSIM_SHOTS_FRONTEND_PORT ?? 4202);
const BRIDGE_DIR = '../tools/screenshot-generator/.runtime/modSettings/FS25_RPSim'; // relative to frontend/ and backend/
const chromium = process.env.CHROMIUM_PATH ?? (existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);

export default defineConfig({
  testDir: '.',
  testMatch: 'screenshots.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 30_000 },
  reporter: 'list',
  outputDir: '.runtime/test-results',
  use: {
    baseURL: `http://localhost:${FRONTEND_PORT}`,
    locale: 'de-DE',
    colorScheme: 'dark',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    launchOptions: chromium ? { executablePath: chromium } : {},
  },
  webServer: [
    {
      name: 'bridge-simulator',
      command:
        `node src/cli.js --dir ../screenshot-generator/.runtime/modSettings/FS25_RPSim --scenario wohlhabender-hof ` +
        `--reset --interval 1000 --game-minutes-per-tick 10 --control-port ${SIM_PORT}`,
      cwd: '../bridge-simulator',
      url: `http://localhost:${SIM_PORT}/state`,
      reuseExistingServer: false,
      timeout: 30_000,
    },
    {
      name: 'backend',
      command:
        '(ls target/rpsim-backend-*.jar >/dev/null 2>&1 || mvn -B -q -DskipTests package) && ' +
        'java -jar $(ls -t target/rpsim-backend-*.jar | head -1) --spring.profiles.active=e2e --rpsim.ai.provider=NONE ' +
        `--rpsim.bridge.path=${BRIDGE_DIR} --rpsim.ai.local-config-file=../tools/screenshot-generator/.runtime/ai.properties`,
      cwd: '../../backend',
      url: 'http://localhost:8080/actuator/health',
      reuseExistingServer: false,
      timeout: 240_000,
    },
    {
      name: 'frontend',
      command: `npx ng serve --port ${FRONTEND_PORT}`,
      cwd: '../../frontend',
      url: `http://localhost:${FRONTEND_PORT}`,
      reuseExistingServer: false,
      timeout: 180_000,
    },
  ],
});
