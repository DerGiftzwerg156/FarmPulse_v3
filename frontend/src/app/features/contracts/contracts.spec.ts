import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CaseView, ContractView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { Contracts } from './contracts';

const contract = (over: Partial<ContractView> = {}): ContractView => ({
  id: 1, kind: 'INSURANCE', status: 'OFFERED', character: null, level: 'BASIC', farmlandId: null, monthlyAmount: 50,
  coveragePercent: 60, deductible: 2000, termMonths: null, startedAtGameTime: null, endsAtGameTime: null,
  nextDueGameTime: null, offerExpiresAtGameTime: 12 * DAY, missedPayments: 0, paymentOverdue: false, endReason: null, ...over,
});
const damage = (over: Partial<CaseView> = {}): CaseView => ({
  id: 7, kind: 'HAIL_DAMAGE', status: 'AWAITING_PLAYER', character: null, farmlandId: 12, hectares: 4.5, damageAmount: 3600,
  payoutAmount: null, costAmount: null, offerAmount: null, roundsUsed: 0, measureAgreed: false, reference: null,
  gameTime: 10 * DAY, deadlineGameTime: 15 * DAY, resolution: null, ...over,
});

describe('Contracts', () => {
  function setup(contracts: ContractView[], cases: CaseView[]) {
    TestBed.configureTestingModule({
      imports: [Contracts],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Contracts);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/contracts').flush(contracts);
    http.expectOne('/api/cases').flush(cases);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the insurance offer with its conditions and accepts it', () => {
    const { fixture, http, el } = setup([contract()], []);
    http.expectOne('/api/insurance/quotes').flush([{ level: 'COMFORT', monthlyPremium: 131, coveragePercent: 90, deductible: 500 }]);
    fixture.detectChanges();
    const offer = el.querySelector('[data-testid="insurance-offer"]')!;
    expect(offer.textContent).toContain('Basis');
    expect(offer.textContent).toContain('60 % Erstattung');
    expect(el.querySelectorAll('[data-testid="insurance-quote"]').length).toBe(1);
    (el.querySelector('[data-testid="offer-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/contracts/1/accept').flush(contract({ status: 'ACTIVE' }));
    http.expectOne('/api/contracts').flush([contract({ status: 'ACTIVE', nextDueGameTime: 11 * DAY })]);
    http.expectOne('/api/cases').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="insurance-active"]')?.textContent).toContain('Aktiv');
  });

  it('reports a damage by phone', () => {
    const { fixture, http, el } = setup([contract({ status: 'ACTIVE' })], [damage(), damage({ id: 8, status: 'SETTLED', resolution: 'PAID', payoutAmount: 160 })]);
    expect(el.querySelector('[data-testid="case"]')?.textContent).toContain('Hagelschaden');
    expect(el.querySelector('[data-testid="closed-case"]')?.textContent).toContain('Ausgezahlt');
    (el.querySelector('[data-testid="report-call"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/cases/7/report');
    expect(req.request.body).toEqual({ channel: 'CALL' });
    req.flush(damage({ status: 'SETTLED' }));
    http.expectOne('/api/contracts').flush([]);
    http.expectOne('/api/cases').flush([]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="case"]')).toBeNull();
  });
});
