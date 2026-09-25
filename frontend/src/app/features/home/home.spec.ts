import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, message, savegame } from '../../../testing/fixtures';
import { Home, buildFeed } from './home';

describe('buildFeed', () => {
  it('merges mails, calls and diary newest first and skips own messages', () => {
    const feed = buildFeed(
      [message({ id: 1, gameTime: 3 * DAY }), message({ id: 2, gameTime: 9 * DAY, initiatedBy: 'PLAYER' })],
      [message({ id: 3, channel: 'CALL', callStatus: 'MISSED', gameTime: 5 * DAY })],
      [{ id: 4, gameTime: 4 * DAY, gameDay: 4, entryType: 'AUTO', category: 'CREDIT', title: 'Kredit', text: null }],
    );
    expect(feed.map((f) => f.key)).toEqual(['c3', 'd4', 'm1']);
    expect(feed[0]).toMatchObject({ link: '/calls', query: { id: 3 }, unread: true });
  });
});

describe('Home', () => {
  function setup(active = true) {
    TestBed.configureTestingModule({
      imports: [Home],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const store = TestBed.inject(GameStateStore);
    store.loaded.set(true);
    if (active) store.savegame.set(savegame({ balance: 245000, unreadMails: 1, pendingCalls: 1, gameDay: 5 }));
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, http, store, el: fixture.nativeElement as HTMLElement };
  }

  function flushAll(http: HttpTestingController) {
    http.expectOne('/api/mails').flush([message({ id: 1, subject: 'Kreditantrag', gameTime: 4 * DAY })]);
    http.expectOne('/api/calls').flush([message({ id: 2, channel: 'CALL', callStatus: 'RINGING', subject: 'Kurze Frage', gameTime: 5 * DAY })]);
    http.expectOne('/api/diary').flush([{ id: 3, gameTime: 0, gameDay: 0, entryType: 'AUTO', category: 'BACKSTORY', title: 'Wie alles begann', text: '…' }]);
    http.expectOne('/api/loans').flush([{ id: 1, status: 'ACTIVE', remainingAmount: 45000, overdue: true }]);
    http.expectOne('/api/negotiations').flush([{ id: 1, status: 'OPEN' }, { id: 2, status: 'ACCEPTED' }]);
    http.expectOne('/api/village-reputation').flush({ tier: 'GOOD', label: 'gut angesehen' });
    http.expectOne('/api/storage').flush({ gameTime: 5 * DAY, totalValue: 41400, items: [{ fillType: 'WHEAT', amount: 180000, capacity: 200000, bestPrice: 230, bestSellPoint: 'Mühle', value: 41400 }] });
  }

  it('invites to the onboarding without a savegame', () => {
    const { el, http } = setup(false);
    expect(el.querySelector('[data-testid="welcome-start"]')?.getAttribute('href')).toBe('/onboarding');
    http.expectNone('/api/mails');
  });

  it('shows the key figure tiles', () => {
    const { el, http, fixture } = setup();
    flushAll(http);
    fixture.detectChanges();
    const value = (id: string) => el.querySelector(`[data-testid="${id}"] [data-testid="stat-value"]`)?.textContent?.replace(/\s/g, ' ').trim();
    expect(value('kpi-balance')).toBe('245.000 €');
    expect(value('kpi-mails')).toBe('1');
    expect(value('kpi-calls')).toBe('1');
    expect(value('kpi-loans')).toBe('1');
    expect(value('kpi-negotiations')).toBe('1');
    expect(value('kpi-reputation')).toBe('Gut angesehen');
    expect(el.querySelector('[data-testid="kpi-loans"]')?.textContent?.replace(/\s/g, ' ')).toContain('45.000 €');
  });

  it('lists the latest events and refreshes on live events', () => {
    const { el, http, fixture, store } = setup();
    flushAll(http);
    fixture.detectChanges();
    const kinds = [...el.querySelectorAll('[data-testid="feed-item"]')].map((i) => i.getAttribute('data-kind'));
    expect(kinds).toEqual(['call', 'mail', 'diary']);
    store.mailVersion.update((v) => v + 1);
    fixture.detectChanges();
    flushAll(http);
    expect(el.querySelector('[data-testid="silo-preview"]')?.textContent).toContain('Weizen');
  });
});
