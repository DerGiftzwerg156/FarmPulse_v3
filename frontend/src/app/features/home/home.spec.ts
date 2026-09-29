import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, message, savegame } from '../../../testing/fixtures';
import { APPS } from '../../layout/apps';
import { Home } from './home';

describe('Home (start screen)', () => {
  function setup(active = true) {
    TestBed.configureTestingModule({
      imports: [Home],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const store = TestBed.inject(GameStateStore);
    store.loaded.set(true);
    if (active) {
      store.savegame.set(savegame({ balance: 245000, unreadMails: 1, pendingCalls: 1, gameDay: 5, gameTime: 5 * DAY + 11.5 * 3_600_000 }));
    }
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, http, store, el: fixture.nativeElement as HTMLElement };
  }

  function flushAll(http: HttpTestingController, phase: string | null = 'HARVESTABLE') {
    http.expectOne('/api/mails').flush([message({ id: 1, subject: 'Kreditantrag', gameTime: 4 * DAY })]);
    http.expectOne('/api/loans').flush([{ id: 1, status: 'ACTIVE', remainingAmount: 45000, overdue: true }]);
    http.expectOne('/api/village-reputation').flush({ tier: 'GOOD', label: 'gut angesehen' });
    http.expectOne('/api/finances').flush({
      available: true,
      months: [
        { year: 1, period: 2, complete: true, operatingResult: 8420, operatingIncome: 0, operatingExpenses: 0, investment: 0, divestment: 0, financing: 0, ignored: 0, lines: [] },
        { year: 1, period: 3, complete: false, operatingResult: -100, operatingIncome: 0, operatingExpenses: 0, investment: 0, divestment: 0, financing: 0, ignored: 0, lines: [] },
      ],
    });
    http.expectOne('/api/farmlands').flush([
      { farmlandId: 3, hectares: 4, referencePrice: 1, ownerType: 'PLAYER', owner: null, inNegotiation: false, phase },
      { farmlandId: 4, hectares: 4, referencePrice: 1, ownerType: 'CHARACTER', owner: null, inNegotiation: false, phase: null },
    ]);
  }

  it('invites to the onboarding without a savegame', () => {
    const { el, http } = setup(false);
    expect(el.querySelector('[data-testid="welcome-start"]')?.getAttribute('href')).toBe('/onboarding');
    http.expectNone('/api/mails');
  });

  it('shows the game clock, the key figures and every app', () => {
    const { el, http, fixture } = setup();
    flushAll(http);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="clock"]')?.textContent?.trim()).toBe('11:30');
    const value = (id: string) => el.querySelector(`[data-testid="${id}"] [data-testid="stat-value"]`)?.textContent?.replace(/\s/g, ' ').trim();
    expect(value('kpi-result')).toBe('+8.420 €');
    expect(el.querySelector('[data-testid="kpi-result"]')?.textContent).toContain('April');
    expect(value('kpi-loans')).toBe('45.000 €');
    expect(value('kpi-reputation')).toBe('Gut angesehen');
    expect(el.querySelectorAll('[data-testid="app-grid"] app-tile').length).toBe(APPS.length);
    expect(el.querySelector('[data-testid="app-grid"] [data-testid="app-mail"] [data-testid="app-badge"]')?.textContent?.trim()).toBe('1');
  });

  it('shows unread mails and harvestable fields as widgets and reloads on live events', () => {
    const { el, http, fixture, store } = setup();
    flushAll(http);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mail-widget"]')?.textContent).toContain('Kreditantrag');
    expect(el.querySelector('[data-testid="harvestable"]')?.textContent?.trim()).toBe('1');
    store.mailVersion.update((v) => v + 1);
    fixture.detectChanges();
    flushAll(http, 'GROWING');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="harvestable"]')?.textContent?.trim()).toBe('0');
  });
});
