import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Shell } from './shell';
import { NAV_ITEMS } from './nav-items';
import { FakeEventSource, provideFakeEventSource } from '../../testing/fake-event-source';

describe('Shell', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [Shell],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideFakeEventSource()],
    });
    const fixture = TestBed.createComponent(Shell);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('renders all main areas in the icon rail', () => {
    const { el, http } = setup();
    http.expectOne('/api/savegame').flush(null);
    expect(el.querySelectorAll('[data-testid="icon-rail"] a').length).toBe(NAV_ITEMS.length);
    expect(el.textContent).toContain('Kein Spielstand verknüpft');
  });

  it('shows savegame context, live balance and notification count', () => {
    const { fixture, el, http } = setup();
    http.expectOne('/api/savegame').flush({
      id: 1, savegameId: 'x', mapName: 'Erlengrund', gameTime: 0, gameDay: 12, balance: 245000,
      tonePreset: 'REALISTIC', unreadMails: 2, pendingCalls: 1, reputationTier: 'NEUTRAL',
    });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="savegame-context"]')?.textContent).toContain('Tag 12');
    expect(el.querySelector('[data-testid="balance"]')?.textContent?.replace(/\s/g, ' ')).toContain('245.000 €');
    expect(el.querySelector('[data-testid="notification-count"]')?.textContent?.trim()).toBe('3');
  });

  it('shows the FS25 month of the savegame (TODO T-08)', () => {
    const { fixture, el, http } = setup();
    http.expectOne('/api/savegame').flush({
      id: 1, savegameId: 'x', mapName: 'Erlengrund', gameTime: 0, gameDay: 12, balance: 1, tonePreset: 'REALISTIC',
      unreadMails: 0, pendingCalls: 0, reputationTier: 'NEUTRAL',
      calendar: { period: 8, periodName: null, dayInPeriod: 2, daysPerPeriod: 3, year: 2 },
    });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="calendar"]')?.textContent?.trim()).toBe('Oktober, Jahr 2');
  });

  it('toggles the mobile menu', () => {
    const { fixture, el, http } = setup();
    http.expectOne('/api/savegame').flush(null);
    expect(el.querySelector('[data-testid="mobile-menu"]')).toBeNull();
    (el.querySelector('[data-testid="menu-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mobile-menu"]')).not.toBeNull();
    (el.querySelector('[data-testid="menu-backdrop"]') as HTMLElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mobile-menu"]')).toBeNull();
  });

  it('reacts to a live mail event: counter and live indicator update', () => {
    const { fixture, el, http } = setup();
    http.expectOne('/api/savegame').flush(null);
    expect(el.textContent).toContain('Offline');
    FakeEventSource.last.emit('hello');
    http.expectOne('/api/savegame').flush(null);
    FakeEventSource.last.emit('mail', { id: 1 });
    http.expectOne('/api/savegame').flush({
      id: 1, savegameId: 'x', mapName: 'Erlengrund', gameTime: 0, gameDay: 3, balance: 1,
      tonePreset: 'REALISTIC', unreadMails: 1, pendingCalls: 0, reputationTier: 'NEUTRAL',
    });
    fixture.detectChanges();
    expect(el.textContent).toContain('Live');
    expect(el.querySelector('[data-testid="notification-count"]')?.textContent?.trim()).toBe('1');
  });
});
