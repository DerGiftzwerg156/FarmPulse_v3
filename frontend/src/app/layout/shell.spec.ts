import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Shell } from './shell';
import { NAV_ITEMS } from './nav-items';

describe('Shell', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [Shell],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
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
});
