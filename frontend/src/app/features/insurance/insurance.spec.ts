import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ContractView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { Insurance } from './insurance';

const contract = (over: Partial<ContractView> = {}): ContractView => ({
  id: 1, kind: 'INSURANCE', status: 'OFFERED', character: null, level: 'BASIC', farmlandId: null, monthlyAmount: 50,
  coveragePercent: 60, deductible: 2000, termMonths: null, startedAtGameTime: null, endsAtGameTime: null,
  nextDueGameTime: null, offerExpiresAtGameTime: 12 * DAY, missedPayments: 0, paymentOverdue: false, endReason: null, ...over,
});

describe('Insurance', () => {
  it('shows the insurance offer with its conditions and accepts it; damages are a section of the app', () => {
    TestBed.configureTestingModule({
      imports: [Insurance],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Insurance);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.match('/api/contracts').forEach((r) => r.flush([contract()]));
    http.expectOne('/api/cases').flush([]);
    http.expectOne('/api/insurance/quotes').flush([{ level: 'COMFORT', monthlyPremium: 131, coveragePercent: 90, deductible: 500 }]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const offer = el.querySelector('[data-testid="insurance-offer"]')!;
    expect(offer.textContent).toContain('Basis');
    expect(offer.textContent).toContain('60 % Erstattung');
    expect(el.querySelectorAll('[data-testid="insurance-quote"]').length).toBe(1);
    expect(el.querySelector('[data-testid="damages"]')?.textContent).toContain('Schäden');
    (el.querySelector('[data-testid="offer-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/contracts/1/accept').flush(contract({ status: 'ACTIVE' }));
    http.expectOne('/api/contracts').flush([contract({ status: 'ACTIVE', nextDueGameTime: 11 * DAY })]);
    http.match('/api/tasks').forEach((r) => r.flush({ items: [], waitingPrompts: 0 }));
    http.match('/api/notices').forEach((r) => r.flush([]));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="insurance-active"]')?.textContent).toContain('Aktiv');
  });
});
