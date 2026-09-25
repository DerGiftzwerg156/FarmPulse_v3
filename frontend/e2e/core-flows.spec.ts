import { expect, test } from '@playwright/test';
import { API, advanceDays, press, waitForList } from './helpers';

/**
 * Core flows of the work plan (AP-9.2) in one serial story: the backend starts empty (in-memory DB), the bridge
 * simulator plays the FS25 savegame "wohlhabender-hof", the FAKE AI provider writes the character texts.
 */
test.describe.serial('FarmPulse core flows', () => {
  test('complete onboarding and link the simulated savegame', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('welcome')).toBeVisible();
    await page.getByTestId('welcome-start').click();

    // 1 backstory: structured blocks + free text
    await page.getByTestId('farm-origin').selectOption('INHERITED');
    await page.getByTestId('village-relation').selectOption('CONNECTED');
    await page.getByTestId('starting-capital').fill('150000');
    await page.getByTestId('with-legacy-loan').check();
    await page.getByTestId('legacy-loan').fill('40000');
    await page.getByTestId('free-text').fill('Mein Großvater hat den Hof 1962 gegründet.');
    await press(page, 'next-1');

    // 2 initial staff
    await press(page, 'add-employee');
    await page.getByTestId('employee-role').first().selectOption('MECHANIC');
    await press(page, 'generate');

    // 3 cast preview with reroll
    await expect(page.getByTestId('cast-member').first()).toBeVisible();
    const members = await page.getByTestId('cast-member').count();
    expect(members).toBeGreaterThan(2);
    const firstName = await page.getByTestId('cast-member').first().locator('div.font-display').first().textContent();
    await page.getByTestId('reroll-one').first().click();
    await expect(page.getByTestId('cast-member').first().locator('div.font-display').first()).not.toHaveText(firstName ?? '');
    await press(page, 'reroll-all');
    await expect(page.getByTestId('cast-member')).toHaveCount(members);
    await press(page, 'next-3');

    // 4 hint, 5 link the detected savegame
    await expect(page.getByTestId('step-4')).toContainText('Farming Simulator 25');
    await press(page, 'next-4');
    await expect(page.getByTestId('detected-savegame')).toHaveCount(1);
    await expect(page.getByTestId('step-5')).toContainText('Erlengrund');
    await page.getByTestId('detected-savegame').check();
    await press(page, 'confirm');

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByTestId('kpis')).toBeVisible();
    await expect(page.getByTestId('savegame-context')).toContainText('Erlengrund');
  });

  test('answer a mail', async ({ page, request }) => {
    await waitForList(request, '/mails', 1);
    await page.goto('/mailbox');
    const thread = page.getByTestId('mail-thread').filter({ hasNot: page.getByTestId('village-life-badge') }).last();
    await thread.click();
    await expect(page.getByTestId('mail-detail')).toBeVisible();
    await expect(page.getByTestId('reply-form')).toBeVisible();
    const before = await page.getByTestId('thread-message').count();
    await page.getByTestId('reply-text').fill('Vielen Dank für die freundliche Begrüßung!');
    await press(page, 'reply-send');
    await expect(page.getByTestId('thread-message')).toHaveCount(before + 1);
    // the character answers through the narration pipeline (SSE "mail" -> list and thread reload)
    await expect(page.getByTestId('thread-message')).toHaveCount(before + 2, { timeout: 30_000 });
  });

  test('apply for credit and wait for the decision', async ({ page, request }) => {
    await page.goto('/bank');
    await page.getByTestId('credit-amount').fill('60000');
    await page.getByTestId('credit-purpose').fill('Neuer Traktor');
    await page.getByTestId('credit-term').fill('36');
    await press(page, 'credit-submit');
    const application = page.getByTestId('application').first();
    await expect(application).toHaveAttribute('data-state', 'processing');
    await expect(application).toContainText('In Bearbeitung');

    await advanceDays(request, 3); // processing time is 1-2 game days
    await expect(application).not.toHaveAttribute('data-state', 'processing', { timeout: 30_000 });
    const state = await application.getAttribute('data-state');
    expect(['approved', 'counter', 'rejected']).toContain(state);
    if (state === 'counter') {
      await press(page, 'counter-accept');
      await expect(application).toHaveAttribute('data-state', 'accepted');
    }
    if (state !== 'rejected') {
      await expect(page.getByTestId('loan').first()).toBeVisible();
    }
  });

  test('accept and decline incoming calls (interviews by phone)', async ({ page }) => {
    await page.goto('/employees');
    await page.getByTestId('posting-role').selectOption('MACHINE_OPERATOR');
    await press(page, 'posting-create');
    await expect(page.getByTestId('applicant').first()).toBeVisible();

    // interview #1 by phone -> the applicant calls back -> accept
    await press(page, 'interview-open', 0);
    await page.getByTestId('interview-text').fill('Welche Maschinen bist du schon gefahren?');
    await page.getByTestId('channel-call').check();
    await press(page, 'interview-send');
    await expect(page.getByTestId('call-overlay')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('decline-hint')).toContainText('Vertrauen');
    await press(page, 'call-accept');
    await expect(page).toHaveURL(/\/calls\?id=\d+/);
    await expect(page.getByTestId('state-accepted')).toBeVisible();
    await expect(page.getByTestId('soft-window')).toBeVisible();
    await page.getByTestId('call-text').fill('Danke für den Rückruf!');
    await press(page, 'call-say');
    await expect(page.getByTestId('call-line')).toHaveCount(2);
    await press(page, 'hang-up');
    await expect(page.getByTestId('state-completed')).toBeVisible();

    // interview #2 by phone -> decline
    await page.goto('/employees');
    await page.getByTestId('posting-toggle').first().click();
    await press(page, 'interview-open', 1);
    await page.getByTestId('interview-text').fill('Kannst du auch am Wochenende?');
    await page.getByTestId('channel-call').check();
    await press(page, 'interview-send');
    await expect(page.getByTestId('call-overlay')).toBeVisible({ timeout: 30_000 });
    await press(page, 'call-decline');
    await expect(page.getByTestId('call-overlay')).toBeHidden();
    await page.goto('/calls');
    await expect(page.getByTestId('call-status').filter({ hasText: 'Abgelehnt' })).toHaveCount(1);
  });

  test('hire an employee and dismiss them again', async ({ page, request }) => {
    const all = (await (await request.get(`${API}/employees`)).json()) as { status: string }[];
    const staffBefore = all.filter((e) => e.status === 'ACTIVE').length; // initial staff from the onboarding
    await page.goto('/employees');
    await expect(page.getByTestId('employee')).toHaveCount(staffBefore);
    await page.getByTestId('posting-toggle').first().click();
    await press(page, 'hire', 0);
    await expect(page.getByTestId('employees-message')).toContainText('ist jetzt im Team');
    await expect(page.getByTestId('employee')).toHaveCount(staffBefore + 1);

    await press(page, 'dismiss-open', staffBefore);
    await press(page, 'dismiss-confirm');
    await expect(page.getByTestId('employees-message')).toContainText('wurde gekündigt');
    await expect(page.getByTestId('employee')).toHaveCount(staffBefore);
  });

  test('negotiate a field with its owner until the deal is closed', async ({ page, request }) => {
    const fields = (await (await request.get(`${API}/farmlands`)).json()) as { farmlandId: number; ownerType: string; referencePrice: number; inNegotiation: boolean }[];
    const target = fields.find((f) => f.ownerType === 'CHARACTER' && !f.inNegotiation);
    expect(target, 'an NPC-owned field exists').toBeTruthy();

    await page.goto('/farmland');
    await page.getByTestId('field-tile').nth(fields.indexOf(target!)).click();
    await press(page, 'start-direct');
    await expect(page.getByTestId('negotiation-detail')).toContainText('Direktverhandlung');
    await expect(page.getByTestId('rounds')).toHaveText('0 / 3');

    // round 1: a low bid -> counter or rejection, round 2: a fair bid is accepted
    await page.getByTestId('offer-amount').fill(String(Math.round(target!.referencePrice * 0.5)));
    await press(page, 'offer-submit');
    await expect(page.getByTestId('rounds')).toHaveText('1 / 3');
    await expect(page.getByTestId('offer-history').locator('tbody tr')).toHaveCount(1);
    await page.getByTestId('offer-amount').fill(String(Math.round(target!.referencePrice * 1.15)));
    await press(page, 'offer-submit');
    await expect(page.getByTestId('negotiation-status')).toHaveText('Abgeschlossen');
    await expect(page.getByTestId('final-price')).toBeVisible();

    // the field changes hands once the simulator applied the farmland transfer
    await expect
      .poll(async () => ((await (await request.get(`${API}/farmlands`)).json()) as typeof fields).find((f) => f.farmlandId === target!.farmlandId)?.ownerType, { timeout: 30_000 })
      .toBe('PLAYER');
  });

  test('price history chart loads data', async ({ page, request }) => {
    await advanceDays(request, 2);
    await page.goto('/market');
    await expect(page.getByTestId('storage-item').first()).toBeVisible();
    await expect(page.getByTestId('chart-line').first()).toBeAttached(); // a flat series has a zero-height box
    await page.getByTestId('range-0').locator('button').click();
    await page.getByTestId('chart-toggle').click();
    expect(await page.getByTestId('chart-table').locator('tbody tr').count()).toBeGreaterThan(2);
  });
});
