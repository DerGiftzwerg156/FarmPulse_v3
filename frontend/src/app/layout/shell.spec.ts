import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Shell } from './shell';
import { Router } from '@angular/router';
import { Component } from '@angular/core';
import { FakeEventSource, provideFakeEventSource } from '../../testing/fake-event-source';

@Component({ template: '' })
class Blank {}

describe('Shell', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [Shell],
      providers: [provideRouter([{ path: '**', component: Blank }]), provideHttpClient(), provideHttpClientTesting(), provideFakeEventSource()],
    });
    const fixture = TestBed.createComponent(Shell);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the floating dock on the start screen and no app header', () => {
    const { fixture, el, http } = setup();
    http.expectOne('/api/savegame').flush(null);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="dock"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="app-header"]')).toBeNull();
    expect(el.textContent).toContain('Kein Spielstand verknüpft');
  });

  it('shows the app header with the way back and the quick bar inside an app', async () => {
    const { fixture, el, http } = setup();
    http.expectOne('/api/savegame').flush(null);
    await TestBed.inject(Router).navigateByUrl('/bank?application=5');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="app-title"]')?.textContent?.trim()).toBe('Bank');
    expect(el.querySelector('[data-testid="back-to-start"]')?.getAttribute('href')).toBe('/');
    expect(el.querySelector('[data-testid="quick-bar"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="dock"]')).toBeNull();
  });

  it('shows savegame context, live balance and notification count', () => {
    const { fixture, el, http } = setup();
    http.expectOne('/api/savegame').flush({
      id: 1, savegameId: 'x', mapName: 'Erlengrund', gameTime: 0, gameDay: 12, balance: 245000,
      tonePreset: 'REALISTIC', unreadMails: 2, pendingCalls: 1, reputationTier: 'NEUTRAL',
      weather: { raining: false, rainFallScale: 0, groundWetness: 0.1, temperature: 16 },
    });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="savegame-context"]')?.textContent).toContain('Tag 12 · 00:00');
    expect(el.querySelector('[data-testid="balance"]')?.textContent?.replace(/\s/g, ' ')).toContain('245.000 €');
    expect(el.querySelector('[data-testid="weather"]')?.textContent?.replace(/\s/g, ' ')).toContain('Trocken · 16 °C');
    const badge = (id: string) => el.querySelector(`[data-testid="dock"] [data-testid="app-${id}"] [data-testid="app-badge"]`)?.textContent?.trim();
    expect(badge('mail')).toBe('2');
    expect(badge('phone')).toBe('1');
  });

  it('shows the FS25 month of the savegame (TODO T-08)', () => {
    const { fixture, el, http } = setup();
    http.expectOne('/api/savegame').flush({
      id: 1, savegameId: 'x', mapName: 'Erlengrund', gameTime: 0, gameDay: 12, balance: 1, tonePreset: 'REALISTIC',
      unreadMails: 0, pendingCalls: 0, reputationTier: 'NEUTRAL',
      calendar: { period: 8, periodName: null, dayInPeriod: 2, daysPerPeriod: 3, year: 2, season: 'AUTUMN' },
    });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="calendar"]')?.textContent?.trim()).toBe('Oktober, Jahr 2 · Herbst');
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
    expect(el.querySelector('[data-testid="dock"] [data-testid="app-mail"] [data-testid="app-badge"]')?.textContent?.trim()).toBe('1');
  });
});
