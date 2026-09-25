import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { apiErrorCode, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { TrustMeter } from '../../features/village/trust-meter';
import { PageErrorView } from '../ui/page-error';
import { LabelPipe } from './label.pipe';
import { splitLink } from './link';

describe('LabelPipe', () => {
  it('translates enum values and falls back to the raw value', () => {
    TestBed.configureTestingModule({ providers: [TranslationService] });
    const pipe = TestBed.runInInjectionContext(() => new LabelPipe());
    expect(pipe.transform('MECHANIC', 'jobRole')).toBe('Mechaniker:in');
    expect(pipe.transform('SOMETHING_NEW', 'jobRole')).toBe('SOMETHING_NEW');
    expect(pipe.transform(null, 'jobRole')).toBe('–');
  });
});

describe('splitLink', () => {
  it('splits app-internal form links', () => {
    expect(splitLink('/bank?application=5')).toEqual({ path: '/bank', queryParams: { application: '5' } });
    expect(splitLink('/farmland')).toEqual({ path: '/farmland', queryParams: {} });
  });
});

describe('api errors', () => {
  it('extracts message, field errors and code of an ApiError', () => {
    const err = new HttpErrorResponse({ status: 400, error: { code: 'VALIDATION', message: 'Eingabe ungültig', fields: { amount: 'muss positiv sein' } } });
    expect(apiErrorMessage(err, 'x')).toBe('Eingabe ungültig: muss positiv sein');
    expect(apiErrorCode(err)).toBe('VALIDATION');
    expect(toPageError(err, 'x')).toEqual({ message: 'Eingabe ungültig: muss positiv sein', code: 'VALIDATION' });
  });

  it('falls back for network errors', () => {
    const err = new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') });
    expect(apiErrorMessage(err, 'Fehler')).toBe('Fehler');
    expect(apiErrorCode(new Error('x'))).toBeNull();
  });
});

describe('PageErrorView', () => {
  it('offers the onboarding for NO_ACTIVE_SAVEGAME and shows other errors', () => {
    TestBed.configureTestingModule({ imports: [PageErrorView], providers: [provideRouter([])] });
    const f = TestBed.createComponent(PageErrorView);
    f.componentRef.setInput('error', { code: 'NO_ACTIVE_SAVEGAME', message: 'x' });
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('a')?.getAttribute('href')).toBe('/onboarding');
    f.componentRef.setInput('error', { code: 'BOOM', message: 'Kaputt' });
    f.detectChanges();
    expect(el.querySelector('[data-testid="page-error"]')?.textContent).toContain('Kaputt');
  });
});

describe('TrustMeter', () => {
  it('fills segments by level and shows a word, never a number', () => {
    TestBed.configureTestingModule({ imports: [TrustMeter] });
    const f = TestBed.createComponent(TrustMeter);
    f.componentRef.setInput('level', 'VERY_GOOD');
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.bg-accent').length).toBe(5);
    expect(el.textContent?.trim()).toBe('Sehr vertraut');
    f.componentRef.setInput('level', 'STRAINED');
    f.detectChanges();
    expect(el.querySelectorAll('.bg-warn').length).toBe(2);
    expect(el.querySelectorAll('.bg-border').length).toBe(3);
  });
});

describe('ApiService', () => {
  it('drops empty price-history filters', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(ApiService).priceHistory({ fillType: 'WHEAT', sellPoint: '', from: undefined }).subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/prices/history');
    expect(req.request.params.keys()).toEqual(['fillType']);
  });

  it('uses PUT for AI settings and DELETE for dismissal', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const api = TestBed.inject(ApiService);
    const http = TestBed.inject(HttpTestingController);
    api.saveAiSettings({ provider: 'NONE' }).subscribe();
    api.dismiss(3).subscribe();
    expect(http.expectOne('/api/settings/ai').request.method).toBe('PUT');
    expect(http.expectOne('/api/employees/3').request.method).toBe('DELETE');
  });
});

describe('GameStateStore', () => {
  it('derives counters from the savegame and marks itself loaded even on errors', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const store = TestBed.inject(GameStateStore);
    const http = TestBed.inject(HttpTestingController);
    store.refresh();
    http.expectOne('/api/savegame').flush({ unreadMails: 2, pendingCalls: 0 });
    expect(store.unreadMails()).toBe(2);
    expect(store.hasNotifications()).toBe(true);
    store.refresh();
    http.expectOne('/api/savegame').flush(null, { status: 500, statusText: 'x' });
    expect(store.loaded()).toBe(true);
  });
});
