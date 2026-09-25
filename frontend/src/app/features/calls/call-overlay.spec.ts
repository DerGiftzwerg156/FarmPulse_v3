import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { message, savegame } from '../../../testing/fixtures';
import { CallOverlay } from './call-overlay';

describe('CallOverlay', () => {
  const ringing = message({ id: 5, channel: 'CALL', callStatus: 'RINGING', subject: 'Kurze Frage zum Kredit', ringDeadlineGameTime: 2 * 86_400_000 });

  function setup() {
    TestBed.configureTestingModule({
      imports: [CallOverlay],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const store = TestBed.inject(GameStateStore);
    store.savegame.set(savegame());
    const fixture = TestBed.createComponent(CallOverlay);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, http, store, el: fixture.nativeElement as HTMLElement };
  }

  it('stays hidden without a ringing call', () => {
    const { http, el, fixture } = setup();
    http.expectOne('/api/calls/pending').flush([{ ...ringing, callStatus: 'ACCEPTED' }]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="call-overlay"]')).toBeNull();
  });

  it('pops up on a live call event with caller and decline consequences', () => {
    const { http, el, fixture, store } = setup();
    http.expectOne('/api/calls/pending').flush([]);
    store.callVersion.update((v) => v + 1);
    fixture.detectChanges();
    http.expectOne('/api/calls/pending').flush([ringing]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="caller"]')?.textContent).toContain('Heike Brandt');
    expect(el.querySelector('[data-testid="decline-hint"]')?.textContent).toContain('Vertrauen');
    expect(el.textContent).toContain('Tag 2, 00:00');
  });

  it('accepting opens the conversation', () => {
    const { http, el, fixture } = setup();
    const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http.expectOne('/api/calls/pending').flush([ringing]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="call-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/calls/5/accept').flush({ ...ringing, callStatus: 'ACCEPTED' });
    fixture.detectChanges();
    expect(nav).toHaveBeenCalledWith(['/calls'], { queryParams: { id: 5 } });
    expect(el.querySelector('[data-testid="call-overlay"]')).toBeNull();
  });

  it('declining closes the overlay', () => {
    const { http, el, fixture } = setup();
    http.expectOne('/api/calls/pending').flush([ringing]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="call-decline"] button') as HTMLButtonElement).click();
    http.expectOne('/api/calls/5/decline').flush({ ...ringing, callStatus: 'DECLINED' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="call-overlay"]')).toBeNull();
  });
});
