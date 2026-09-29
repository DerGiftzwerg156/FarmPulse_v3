import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
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

  function flushTasks(http: HttpTestingController) {
    http.match('/api/tasks').forEach((r) => r.flush({
      waitingPrompts: 0,
      items: [{ key: 'credit-1', type: 'CREDIT_COUNTER', kind: 'COUNTER_OFFER', deadlineGameTime: null, gameTime: 4 * DAY,
        serviceCase: null, contract: null, application: { id: 1, amount: 80000, offeredAmount: 60000 }, call: null,
        negotiation: null, marketEvent: null, posting: null, pendingApplicants: null }],
    }));
    http.match('/api/notices').forEach((r) => r.flush([]));
  }

  function flushAll(http: HttpTestingController, phase: string | null = 'HARVESTABLE') {
    http.expectOne('/api/calendar').flush({ gameTime: 5 * DAY, currentPeriod: 2, year: 1, daysPerPeriod: 1, nextMonthStart: 6 * DAY,
      nextPeriod: 3, agenda: [], monthStartDebits: [], monthStartTotal: 8315, yearEvents: [] });
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
    expect(value('kpi-debits')).toBe('−8.315 €');
    expect(el.querySelector('[data-testid="kpi-debits"]')?.textContent).toContain('Mai');
    expect(value('kpi-reputation')).toBe('Gut angesehen');
    expect(el.querySelectorAll('[data-testid="app-grid"] app-tile').length).toBe(APPS.length);
    expect(el.querySelector('[data-testid="app-grid"] [data-testid="app-mail"] [data-testid="app-badge"]')?.textContent?.trim()).toBe('1');
    flushTasks(http);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="app-grid"] [data-testid="app-bank"] [data-testid="app-badge"]')?.textContent?.trim()).toBe('1');
  });

  it('shows open tasks and harvestable fields as widgets and reloads on live events', () => {
    const { el, http, fixture, store } = setup();
    flushAll(http);
    flushTasks(http);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="todo-count"]')?.textContent?.trim()).toBe('1');
    expect(el.querySelector('[data-testid="todo"]')?.textContent).toContain('Gegenangebot der Bank');
    expect(el.querySelector('[data-testid="todo"]')?.textContent).toContain('Bank');
    expect(el.querySelector('[data-testid="harvestable"]')?.textContent?.trim()).toBe('1');
    store.stateVersion.update((v) => v + 1);
    fixture.detectChanges();
    flushAll(http, 'GROWING');
    flushTasks(http);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="harvestable"]')?.textContent?.trim()).toBe('0');
  });
});
