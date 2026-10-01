import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ContractView, DroughtStatusView } from '../../core/api/models';
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
    http.match('/api/drought').forEach((r) => r.flush(droughtStatus()));
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
    http.match('/api/drought').forEach((r) => r.flush(droughtStatus()));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="insurance-active"]')?.textContent).toContain('Aktiv');
  });

  it('shows the drought cover beside storm and hail: rain of the months, quote, offer and declared droughts', () => {
    TestBed.configureTestingModule({
      imports: [Insurance],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Insurance);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.match('/api/contracts').forEach((r) => r.flush([contract({ status: 'ACTIVE' }),
      contract({ id: 2, level: 'DROUGHT_INDEX', monthlyAmount: 18, coveragePercent: null, deductible: null })]));
    http.match('/api/cases').forEach((r) => r.flush([]));
    http.match('/api/drought').forEach((r) => r.flush(droughtStatus()));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    // the storm/hail card shows the BASIC insurance only
    expect(el.querySelector('[data-testid="insurance-active"]')?.textContent).toContain('Basis');
    const card = el.querySelector('[data-testid="drought-insurance"]')!;
    expect(card.querySelector('[data-testid="drought-terms"]')?.textContent).toContain('4');
    expect(card.querySelector('[data-testid="drought-offer"]')?.textContent).toContain('Dürre-Index');
    expect(card.querySelector('[data-testid="drought-quote"]')?.textContent).toContain('4.5 ha');
    const months = card.querySelectorAll('[data-testid="drought-month"]');
    expect(months.length).toBe(2);
    expect(months[0].textContent).toContain('Trocken');
    expect(months[1].textContent).toContain('Läuft');
    expect(card.querySelector('[data-testid="drought-series"]')?.textContent).toContain('Mai');
    const entry = card.querySelector('[data-testid="drought-entry"]')!;
    expect(entry.textContent).toContain('Weizen');
    expect(entry.textContent).toContain('Versicherung zahlte');
    (card.querySelector('[data-testid="drought-request"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/insurance/offer');
    expect(req.request.body).toEqual({ level: 'DROUGHT_INDEX' });
  });
});

function droughtStatus(): DroughtStatusView {
  return {
    enabled: true, growthMonths: ['Mai', 'Juni'], minPeriods: 2, maxRainPercent: 3, minObservedPercent: 50,
    dryMonths: 1, seriesStartMonth: 'Mai', seriesDeclared: false,
    months: [{ monthIndex: 14, month: 'Mai', rainPercent: 1, observedPercent: 100, rating: 'DRY' },
      { monthIndex: 15, month: 'Juni', rainPercent: null, observedPercent: 0, rating: 'RUNNING' }],
    quote: { hectares: 4.5, premiumPerHectare: 4, monthlyPremium: 18, payoutPerHectare: 200, payout: 900 },
    aidPerHectare: 150, aidDeductionPercent: 50,
    droughts: [{ id: 1, firstMonth: 'Mai', lastMonth: 'Juni', dryMonths: 2, declaredGameTime: 16 * DAY, crops: ['WHEAT'],
      priceEvents: 2, insuranceResult: 'PAID', insuredHectares: 4.5, insurancePayout: 900, aidHectares: 4.5, aidCaseId: 3 }],
  };
}
