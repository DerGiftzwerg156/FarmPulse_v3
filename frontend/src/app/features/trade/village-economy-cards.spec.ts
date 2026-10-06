import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CooperativeView, FarmHolidayView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { CoopSharesCard } from './coop-shares-card';
import { FarmHolidayCard } from './farm-holiday-card';

const holiday = (over: Partial<FarmHolidayView> = {}): FarmHolidayView => ({
  enabled: true, setupCost: 20000, baseIncomePerMonth: 800, since: null,
  preview: { period: 5, seasonFactor: 1.5, reputationFactor: 1, animalFactor: 1.2, noise: false, smell: false, badReview: false,
    income: 1440 }, months: [], ...over,
});
const coop = (over: Partial<CooperativeView> = {}): CooperativeView => ({
  enabled: true, sharePrice: 500, maxShares: 200, shares: 0, noticedShares: 0, board: false, boardMissed: 0, boardMinShares: 40,
  dividendRatePercent: 4, dividendBonusPercent: 0, grainStore: false, noticeMonths: 12, notices: [], ...over,
});

function setup<T>(component: Type<T>, url: string, body: object) {
  TestBed.configureTestingModule({ imports: [component], providers: [provideHttpClient(), provideHttpClientTesting()] });
  TestBed.inject(GameStateStore).savegame.set(savegame({}));
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne(url).flush(body);
  fixture.detectChanges();
  return { fixture, http, el: fixture.nativeElement as HTMLElement };
}

describe('FarmHolidayCard (R31-D6)', () => {
  it('sets the holiday flat up by button', () => {
    const { el, http } = setup(FarmHolidayCard, '/api/farm-holiday', holiday());
    (el.querySelector('[data-testid="holiday-setup"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/farm-holiday').request.method).toBe('POST');
  });

  it('shows the running month and the complaints of past months', () => {
    const { el } = setup(FarmHolidayCard, '/api/farm-holiday', holiday({ since: DAY, months: [
      { monthIndex: 3, period: 6, income: 1152, seasonFactor: 1.5, reputationFactor: 1, animalFactor: 1.2, noise: true, smell: true,
        badReview: false, gameTime: 4 * DAY }] }));
    expect(el.querySelector('[data-testid="holiday-setup"]')).toBeNull();
    expect(el.querySelector('[data-testid="holiday-preview"]')?.textContent).toContain('1.440');
    const month = el.querySelector('[data-testid="holiday-month"]')!;
    expect(month.textContent).toContain('Lärm');
    expect(month.textContent).toContain('Güllegeruch');
  });
});

describe('CoopSharesCard (R31-D7)', () => {
  it('buys and cancels shares with the number of the form', () => {
    const { el, fixture, http } = setup(CoopSharesCard, '/api/cooperative', coop({ shares: 40, board: true }));
    expect(el.querySelector('[data-testid="coop-board"]')).not.toBeNull();
    const count = el.querySelector('[data-testid="coop-count"]') as HTMLInputElement;
    count.value = '10';
    count.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="coop-buy"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/cooperative/shares').request.body).toEqual({ count: 10 });
  });

  it('cancels shares and lists the notice', () => {
    const { el, fixture, http } = setup(CoopSharesCard, '/api/cooperative', coop({ shares: 40 }));
    const count = el.querySelector('[data-testid="coop-count"]') as HTMLInputElement;
    count.value = '5';
    count.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="coop-cancel"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/cooperative/notices');
    expect(req.request.body).toEqual({ count: 5 });
    req.flush(coop({ shares: 40, noticedShares: 5, notices: [{ id: 1, shares: 5, noticedGameTime: DAY, dueGameTime: 13 * DAY,
      paidGameTime: null }] }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="coop-notice"]')?.textContent).toContain('5 Anteile gekündigt');
    expect(el.querySelector('[data-testid="coop-holding"]')?.textContent).toContain('5 gekündigt');
  });
});
