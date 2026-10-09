import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CaseView, FarmReportView, LiquidityPlanView, LoanView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { CaseCard } from '../contracts/case-card';
import { Bank } from './bank';
import { CollateralPicker } from './collateral-picker';
import { FarmReportCard } from './farm-report-card';
import { LiquidityPlanCard } from './liquidity-plan-card';

const providers = [provideRouter([]), provideHttpClient(), provideHttpClientTesting()];

const loan = (over: Partial<LoanView> = {}): LoanView => ({
  id: 4, principal: 60000, remainingAmount: 45000, interestRatePercent: 4.75, termMonths: 36, monthlyInstallment: 1812,
  purpose: 'Halle', status: 'ACTIVE', legacy: false, blocksNewCredit: false, nextDueGameTime: 10 * DAY, overdue: false,
  escalationLevel: 0, missedInstallments: 0, paidInstallments: 8, deferredUntilGameTime: null, history: [],
  remainingInstallments: 28, specialRepayment: { allowed: true, refusal: null, freeAmountLeft: 6000, feeRatePercent: 1, payoffInterest: 120 },
  collateral: [{ farmlandId: 12, collateralValue: 32400, status: 'PLEDGED', saleConsent: false, loanId: 4, purpose: 'Halle' }],
  rateCutTotalPercent: 0.25, ...over,
});

const caseView = (over: Partial<CaseView>): CaseView => ({
  id: 7, kind: 'COLLATERAL_CLAIM', status: 'AWAITING_PLAYER', character: null, farmlandId: 12, hectares: null,
  damageAmount: null, payoutAmount: null, costAmount: null, offerAmount: 32400, roundsUsed: 0, measureAgreed: false,
  reference: '4', gameTime: DAY, deadlineGameTime: 11 * DAY, resolution: null, measureCost: null, title: 'Halle', ...over,
});

describe('CollateralPicker (R3-K1)', () => {
  it('lists the free own fields and shows coverage and discount of the choice', () => {
    TestBed.configureTestingModule({ imports: [CollateralPicker], providers });
    const fixture = TestBed.createComponent(CollateralPicker);
    fixture.componentRef.setInput('amount', 50000);
    const chosen: number[][] = [];
    fixture.componentInstance.changed.subscribe((ids) => chosen.push(ids));
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/credit/collateral').flush({
      loanToValuePercent: 60, requiredAboveSharePercent: 50, maxInterestDiscountPercent: 1, requiredAboveAmount: 280000,
      eligible: [{ farmlandId: 12, hectares: 4.5, price: 54000, collateralValue: 32400 }], pledged: [],
    });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="collateral-hint"]')?.textContent).toContain('280.000');
    (el.querySelector('[data-testid="collateral-option"]') as HTMLInputElement).click();
    fixture.detectChanges();
    expect(chosen.at(-1)).toEqual([12]);
    expect(el.querySelector('[data-testid="collateral-coverage"]')?.textContent).toContain('65 %');
  });
});

describe('Bank with collateral (R3-K1)', () => {
  function setup(loans: LoanView[]) {
    TestBed.configureTestingModule({ imports: [Bank], providers });
    const fixture = TestBed.createComponent(Bank);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/credit-applications').flush([{
      id: 1, amount: 700000, purpose: 'Stall', termMonths: 120, status: 'DECIDED', submittedAtGameTime: DAY,
      decisionVisibleAtGameTime: 2 * DAY, decision: 'COUNTER_OFFER', reasonCategory: 'LOAN_TOO_LARGE_FOR_FARM',
      offeredAmount: 700000, offeredTermMonths: 120, offeredInterestRatePercent: 4.57, loanId: null,
      collateralFarmlandIds: [], proposedFarmlandIds: [14], collateralValue: 300000, coveragePercent: 42.9,
      interestDiscountPercent: 0.43, collateralRequired: true,
    }]);
    http.expectOne('/api/loans').flush(loans);
    http.expectOne('/api/settings/vanilla-bypass').flush({ reactionsEnabled: true, interestSurchargePercent: 0 });
    http.match('/api/credit/collateral').forEach((r) => r.flush({ loanToValuePercent: 60, requiredAboveSharePercent: 50,
      maxInterestDiscountPercent: 1, requiredAboveAmount: 280000, eligible: [], pledged: [] }));
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('asks the bank to allow a sale of the pledged field', () => {
    const { el, http, fixture } = setup([loan()]);
    expect(el.querySelector('[data-testid="loan-collateral"]')?.textContent).toContain('Feld 12');
    expect(el.querySelector('[data-testid="loan-rate-cut"]')?.textContent).toContain('0,25');
    (el.querySelector('[data-testid="sale-consent-request"] button') as HTMLButtonElement).click();
    http.expectOne('/api/credit/collateral/12/sale-consent').flush({ farmlandId: 12, collateralValue: 32400, status: 'PLEDGED', saleConsent: true, loanId: 4, purpose: 'Halle' });
    http.expectOne('/api/credit-applications').flush([]);
    http.expectOne('/api/loans').flush([loan({ collateral: [{ farmlandId: 12, collateralValue: 32400, status: 'PLEDGED', saleConsent: true, loanId: 4, purpose: 'Halle' }] })]);
    http.match('/api/settings/vanilla-bypass').forEach((r) => r.flush({ reactionsEnabled: true, interestSurchargePercent: 0 }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="sale-consent"]')).not.toBeNull();
  });

  it('shows the counter offer "mit Grundschuld" in the tab of the application', () => {
    const { el, http, fixture } = setup([loan()]);
    fixture.componentRef.setInput('tab', 'antrag');
    fixture.detectChanges();
    http.match('/api/credit/collateral').forEach((r) => r.flush({ loanToValuePercent: 60, requiredAboveSharePercent: 50,
      maxInterestDiscountPercent: 1, requiredAboveAmount: 280000, eligible: [], pledged: [] }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="counter-collateral"]')?.textContent).toContain('Feld 14');
  });

  // Roadmap V3 R3-L1
  it('asks the bank to allow a lease of the pledged field', () => {
    const { el, http, fixture } = setup([loan()]);
    (el.querySelector('[data-testid="lease-consent-request"] button') as HTMLButtonElement).click();
    const pledged = { farmlandId: 12, collateralValue: 32400, status: 'PLEDGED', saleConsent: false, loanId: 4, purpose: 'Halle',
      leaseConsent: true };
    http.expectOne('/api/credit/collateral/12/lease-consent').flush(pledged);
    http.expectOne('/api/credit-applications').flush([]);
    http.expectOne('/api/loans').flush([loan({ collateral: [pledged] })]);
    http.match('/api/settings/vanilla-bypass').forEach((r) => r.flush({ reactionsEnabled: true, interestSurchargePercent: 0 }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="lease-consent"]')?.textContent).toContain('Verpachtung erlaubt');
    expect(el.querySelector('[data-testid="sale-consent-request"]')).not.toBeNull();
  });
});

describe('Bank cases (R3-K1 / R3-K3)', () => {
  function card(c: CaseView) {
    TestBed.configureTestingModule({ imports: [CaseCard], providers });
    const fixture = TestBed.createComponent(CaseCard);
    fixture.componentRef.setInput('c', c);
    fixture.detectChanges();
    return { el: fixture.nativeElement as HTMLElement, http: TestBed.inject(HttpTestingController) };
  }

  it('pays a claim after a menu sale and marks it overdue', () => {
    const { el, http } = card(caseView({ resolution: 'OVERDUE' }));
    expect(el.querySelector('[data-testid="collateral-claim"]')?.textContent).toContain('Feld 12');
    expect(el.querySelector('[data-testid="claim-overdue"]')).not.toBeNull();
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/7/accept').flush(caseView({ status: 'SETTLED' }));
  });

  it('shows the invitation and the rate-cut offer of the annual review', () => {
    const { el } = card(caseView({ kind: 'ANNUAL_REVIEW_OFFER', reference: '0.0025', quantity: 3 }));
    expect(el.querySelector('[data-testid="rate-cut-offer"]')?.textContent).toContain('0,25 Prozentpunkte');
    expect(el.querySelector('[data-testid="case-decline"]')).not.toBeNull();
  });
});

describe('LiquidityPlanCard (R3-K2)', () => {
  it('shows the months with known postings, the estimate and the shortfall', () => {
    TestBed.configureTestingModule({ imports: [LiquidityPlanCard], providers });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(LiquidityPlanCard);
    fixture.detectChanges();
    const month = (period: number, balanceEnd: number, extra = {}) => ({
      monthIndex: period, startGameTime: period * DAY, period, year: 2, knownTotal: -5000, incomeEstimate: 2000,
      incomeSource: 'AVERAGE', reserve: 5000, balanceEnd, belowZero: balanceEnd < 0, belowReserve: balanceEnd < 5000,
      postings: [{ kind: 'SALARIES', label: null, amount: -5000, estimate: false }], ...extra,
    });
    const plan: LiquidityPlanView = {
      available: true, journalAvailable: true, balance: 6000, reserveFactor: 1,
      months: [month(3, 3000), month(4, -1000, { postings: [{ kind: 'TAX_PREPAYMENT', label: 'Q2', amount: -3000, estimate: false }] })],
      firstBelowZero: null, firstBelowReserve: null,
    };
    plan.firstBelowZero = plan.months[1];
    TestBed.inject(HttpTestingController).expectOne('/api/liquidity-plan').flush(plan);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="plan-below-zero"]')?.textContent).toContain('Juni');
    expect(el.querySelectorAll('[data-testid="plan-month"]').length).toBe(2);
    (el.querySelectorAll('[data-testid="plan-month"]')[1] as HTMLElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="plan-postings"]')?.textContent).toContain('Steuervorauszahlung');
  });
});

describe('FarmReportCard (R3-K3)', () => {
  it('shows the totals, the yield per field and the comparison with the previous year', () => {
    TestBed.configureTestingModule({ imports: [FarmReportCard], providers });
    const fixture = TestBed.createComponent(FarmReportCard);
    fixture.detectChanges();
    const snapshot = { staff: 2, monthlyWages: 5000, animals: 24, averageHealth: 80, reputationTier: 'GOOD', trust: [] };
    const report: FarmReportView = {
      year: 2, months: 12, income: [{ category: 'HARVEST_INCOME', amount: 30000 }], expenses: [{ category: 'PURCHASE_FUEL', amount: -4000 }],
      totals: { operatingIncome: 30000, operatingExpense: -4000, operatingResult: 26000, investment: 0, divestment: 0, financing: 0 },
      tax: { status: 'ASSESSED', profit: 26000, tax: 3000 },
      fields: [{ farmlandId: 12, fruitType: 'WHEAT', hectares: 4.5, harvested: true, withered: false, yieldLiters: 42750 }],
      rain: [{ period: 3, rainHours: 12, observedHours: 24 }], stables: [], welfareInspections: 0,
      snapshot, previous: { ...snapshot, staff: 1, reputationTier: 'NEUTRAL' },
    };
    TestBed.inject(HttpTestingController).expectOne('/api/farm-reports').flush([report]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="report-tax"]')?.textContent).toContain('3.000');
    expect(el.querySelector('[data-testid="report-fields"]')?.textContent).toContain('42.750 l');
    expect(el.querySelector('[data-testid="report-compare"]')?.textContent).toContain('Vorjahr');
    expect(el.querySelector('[data-testid="report-rain"]')?.textContent).toContain('12');
  });
});
