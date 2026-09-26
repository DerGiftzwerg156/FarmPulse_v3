import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { NoticesCard } from './notices-card';

describe('NoticesCard', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [NoticesCard],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(NoticesCard);
    fixture.detectChanges();
    return { fixture, http: TestBed.inject(HttpTestingController), el: fixture.nativeElement as HTMLElement };
  }

  it('renders nothing without notices', () => {
    const { fixture, http, el } = setup();
    http.expectOne('/api/notices').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="notices"]')).toBeNull();
  });

  it('explains a refused booking and a rewind decision', () => {
    const { fixture, http, el } = setup();
    http.expectOne('/api/notices').flush([
      { id: 1, kind: 'INSTRUCTION_FAILED', status: 'OPEN', gameTime: DAY, relatedType: null, relatedId: null,
        details: { type: 'MONEY_TRANSACTION', reason: 'CREDIT_INSTALLMENT', amount: -1000, message: 'INSUFFICIENT_FUNDS' } },
      { id: 2, kind: 'REWIND_DECISION', status: 'OPEN', gameTime: DAY, relatedType: 'REWIND', relatedId: 7,
        details: { count: 2, moneyTotal: 25000, previousGameTime: 12 * DAY, rewoundToGameTime: 10 * DAY } },
    ]);
    fixture.detectChanges();
    const items = el.querySelectorAll('[data-testid="notice"]');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('Kreditrate');
    expect(items[0].textContent).toContain('zu wenig Geld auf dem Konto');
    expect(items[1].textContent).toContain('2 Buchungen');
    expect(items[1].textContent).toContain('Tag 12');
    expect(items[1].querySelector('[data-testid="notice-resend"]')).not.toBeNull();
  });

  it('sends the decision and removes the notice', () => {
    const { fixture, http, el } = setup();
    http.expectOne('/api/notices').flush([{ id: 2, kind: 'REWIND_DECISION', status: 'OPEN', gameTime: DAY, relatedType: 'REWIND',
      relatedId: 7, details: { count: 1, moneyTotal: 100 } }]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="notice-keep"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/notices/2/resolve');
    expect(req.request.body).toEqual({ action: 'KEEP' });
    req.flush({ id: 2 });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="notice"]')).toBeNull();
  });
});
