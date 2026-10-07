import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GameStateStore } from '../state/game-state.store';
import { ToastService } from '../../shared/ui/toast.service';
import { concurrentUpdateInterceptor } from './concurrent-update.interceptor';

describe('concurrentUpdateInterceptor (review 10/2026 Phase 1.4)', () => {
  function setup() {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([concurrentUpdateInterceptor])), provideHttpClientTesting()],
    });
    return {
      http: TestBed.inject(HttpClient),
      ctrl: TestBed.inject(HttpTestingController),
      store: TestBed.inject(GameStateStore),
      toasts: TestBed.inject(ToastService),
    };
  }

  it('shows a hint, reloads the pages and still hands the error to the caller', () => {
    const { http, ctrl, store, toasts } = setup();
    const before = store.stateVersion();
    let status = 0;
    http.put('/api/settings/farm', {}).subscribe({ error: (e) => (status = e.status) });
    ctrl.expectOne('/api/settings/farm').flush(
      { code: 'CONCURRENT_UPDATE', message: 'x', fields: {} }, { status: 409, statusText: 'Conflict' });
    expect(status).toBe(409);
    expect(store.stateVersion()).toBe(before + 1);
    expect(toasts.toasts()[0].text).toContain('inzwischen geändert');
    expect(toasts.toasts()[0].kind).toBe('warning');
    ctrl.expectOne('/api/savegame'); // the header data is reloaded as well
  });

  it('leaves other conflicts (business rules) alone', () => {
    const { http, ctrl, store, toasts } = setup();
    const before = store.stateVersion();
    http.post('/api/negotiations', {}).subscribe({ error: () => undefined });
    ctrl.expectOne('/api/negotiations').flush(
      { code: 'NEGOTIATION_OPEN', message: 'x', fields: {} }, { status: 409, statusText: 'Conflict' });
    expect(store.stateVersion()).toBe(before);
    expect(toasts.toasts()).toEqual([]);
    ctrl.verify();
  });
});
