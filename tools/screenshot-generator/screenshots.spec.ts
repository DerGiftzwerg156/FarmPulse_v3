import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { APIRequestContext, Page, expect, test } from '@playwright/test';

const OUT = resolve(import.meta.dirname, '../../docs/screenshots');
const SIM = `http://localhost:${process.env.RPSIM_SHOTS_SIM_PORT ?? 8098}`;
const API = 'http://localhost:8080/api';
mkdirSync(OUT, { recursive: true });

async function shot(page: Page, name: string, fullPage = true, showCalls = false): Promise<void> {
  await page.waitForLoadState('networkidle').catch(() => undefined);
  // random incoming calls (market news, story hooks) would cover the page - only the call shots show them
  await page.addStyleTag({ content: `[data-testid="call-overlay"] { display: ${showCalls ? 'block' : 'none'} !important; }` });
  await page.waitForTimeout(400); // let charts measure their width and animations settle
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage });
}

async function press(page: Page, testId: string, nth = 0): Promise<void> {
  await page.getByTestId(testId).nth(nth).locator('button').click();
}

async function advance(request: APIRequestContext, days: number): Promise<void> {
  expect((await request.post(`${SIM}/advance`, { data: { days } })).ok()).toBeTruthy();
}

test.describe.serial('screenshots', () => {
  test('onboarding', async ({ page }) => {
    await page.goto('/');
    await shot(page, '00-willkommen', false);
    await page.getByTestId('welcome-start').click();
    await page.getByTestId('farm-origin').selectOption('INHERITED');
    await page.getByTestId('village-relation').selectOption('UNKNOWN');
    await page.getByTestId('starting-capital').fill('150000');
    await page.getByTestId('free-text').fill('Mein Großvater hat den Hof 1962 gegründet. Seit seinem Tod stand er zwei Jahre leer.');
    await shot(page, '01-onboarding-vorgeschichte');
    await press(page, 'next-1');
    await press(page, 'add-employee');
    await page.getByTestId('employee-role').first().selectOption('MECHANIC');
    await shot(page, '02-onboarding-mitarbeiter');
    await press(page, 'generate');
    await expect(page.getByTestId('cast-member').first()).toBeVisible();
    await shot(page, '03-onboarding-startbesetzung');
    await press(page, 'next-3');
    await shot(page, '04-onboarding-spielstand-laden');
    await press(page, 'next-4');
    await expect(page.getByTestId('detected-savegame')).toHaveCount(1);
    await page.getByTestId('detected-savegame').check();
    await shot(page, '05-onboarding-verknuepfen');
    await press(page, 'confirm');
    await expect(page.getByTestId('kpis')).toBeVisible();
  });

  test('play a few days to fill every page', async ({ page, request }) => {
    // credit application (decision after the processing time)
    await page.goto('/bank');
    await page.getByTestId('credit-amount').fill('80000');
    await page.getByTestId('credit-purpose').fill('Neuer Mähdrescher');
    await page.getByTestId('credit-term').fill('48');
    await press(page, 'credit-submit');
    await page.getByTestId('credit-amount').fill('45000000');
    await page.getByTestId('credit-purpose').fill('Zweiter Hof im Nachbardorf');
    await page.getByTestId('credit-term').fill('120');
    await press(page, 'credit-submit');
    await advance(request, 3);

    // staff: a posting with applicants and one hire
    await page.goto('/employees');
    await page.getByTestId('posting-role').selectOption('MACHINE_OPERATOR');
    await press(page, 'posting-create');
    await expect(page.getByTestId('applicant').first()).toBeVisible();
    await press(page, 'hire', 0);
    await page.getByTestId('posting-role').selectOption('ANIMAL_KEEPER');
    await press(page, 'posting-create');
    await expect(page.getByTestId('applicant').first()).toBeVisible();

    // a direct land negotiation with one counter offer
    const fields = (await (await request.get(`${API}/farmlands`)).json()) as { farmlandId: number; ownerType: string; referencePrice: number; owner: { id: number } | null }[];
    const target = fields.find((f) => f.ownerType === 'CHARACTER');
    if (target?.owner) {
      const n = (await (await request.post(`${API}/negotiations/direct`, { data: { characterId: target.owner.id, farmlandId: target.farmlandId } })).json()) as { id: number };
      await request.post(`${API}/negotiations/${n.id}/offer`, { data: { amount: Math.round(target.referencePrice * 0.88) } });
    }
    // a diary note and some more days for prices, events and village life
    await request.post(`${API}/diary/entries`, { data: { title: 'Erste Ernte', text: 'Der Weizen am Nordfeld stand besser als gedacht.' } });
    await advance(request, 6);
  });

  test('pages', async ({ page, request }) => {
    await page.goto('/');
    await expect(page.getByTestId('feed-item').first()).toBeVisible();
    await shot(page, '10-dashboard');

    await page.goto('/mailbox');
    await page.getByTestId('mail-thread').first().click();
    await expect(page.getByTestId('mail-detail')).toBeVisible();
    await shot(page, '11-postfach', false);

    await page.goto('/bank');
    await expect(page.getByTestId('application').first()).not.toHaveAttribute('data-state', 'processing');
    await shot(page, '12-bank');

    await page.goto('/employees');
    await page.getByTestId('posting-toggle').first().click();
    await expect(page.getByTestId('applicant').first()).toBeVisible();
    await shot(page, '13-personal');

    await page.goto('/farmland');
    await expect(page.getByTestId('field-tile').first()).toBeVisible();
    if (await page.getByTestId('closed-toggle').count()) {
      await page.getByTestId('closed-toggle').click();
      await page.getByTestId('closed-row').first().click();
      await expect(page.getByTestId('negotiation-detail')).toBeVisible();
    }
    await shot(page, '14-felder-verhandlung');

    await page.goto('/market');
    await expect(page.getByTestId('chart-line').first()).toBeAttached(); // a flat series has a zero-height box
    await shot(page, '15-warenbestand-preise');

    const characters = (await (await request.get(`${API}/characters`)).json()) as { id: number; category: string; farmlands: unknown[] }[];
    const person = characters.find((c) => c.farmlands.length > 0) ?? characters.find((c) => c.category === 'DYNAMIC') ?? characters[0];
    await page.goto(`/village?character=${person.id}`);
    await expect(page.getByTestId('character-detail')).toBeVisible();
    await shot(page, '16-dorf-charakter');

    await page.goto('/diary');
    await expect(page.getByTestId('diary-entry').first()).toBeVisible();
    await shot(page, '17-tagebuch');

    await page.goto('/settings');
    await expect(page.getByTestId('ai-settings')).toBeVisible();
    await shot(page, '18-einstellungen');
  });

  test('incoming call and conversation', async ({ page }) => {
    await page.goto('/employees');
    await page.getByTestId('posting-toggle').first().click();
    await press(page, 'interview-open', 0);
    await page.getByTestId('interview-text').fill('Hast du schon mit Milchkühen gearbeitet?');
    await page.getByTestId('channel-call').check();
    await press(page, 'interview-send');
    await expect(page.getByTestId('call-overlay')).toBeVisible();
    await shot(page, '19-anruf-eingehend', false, true);
    await press(page, 'call-accept');
    await expect(page.getByTestId('state-accepted')).toBeVisible();
    await page.getByTestId('call-text').fill('Und wann könntest du anfangen?');
    await press(page, 'call-say');
    await expect(page.getByTestId('call-line')).toHaveCount(3, { timeout: 30_000 });
    await shot(page, '20-anruf-gespraech');
  });

  test('mobile', async ({ browser }) => {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme: 'dark', locale: 'de-DE' });
    await page.goto('http://localhost:' + (process.env.RPSIM_SHOTS_FRONTEND_PORT ?? 4202) + '/');
    await expect(page.getByTestId('kpis')).toBeVisible();
    await shot(page, '21-mobil-dashboard', false);
    await page.close();
  });
});
