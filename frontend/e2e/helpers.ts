import { APIRequestContext, Page, expect } from '@playwright/test';

export const SIM = `http://localhost:${process.env.RPSIM_E2E_SIM_PORT ?? 8099}`;
export const API = 'http://localhost:8080/api';

/** Fast-forwards game time in the bridge simulator (each day is exported and picked up by the backend). */
export async function advanceDays(request: APIRequestContext, days: number): Promise<void> {
  const res = await request.post(`${SIM}/advance`, { data: { days } });
  expect(res.ok()).toBeTruthy();
}

/** Clicks the inner <button> of an <app-button data-testid="..."> (nth match). */
export async function press(page: Page, testId: string, nth = 0): Promise<void> {
  await page.getByTestId(testId).nth(nth).locator('button').click();
}

/** Waits until the backend's narration worker produced at least `min` items of an API list. */
export async function waitForList(request: APIRequestContext, path: string, min: number, predicate: (x: never) => boolean = () => true): Promise<void> {
  await expect
    .poll(async () => ((await (await request.get(`${API}${path}`)).json()) as never[]).filter(predicate).length, { timeout: 30_000 })
    .toBeGreaterThanOrEqual(min);
}
