import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CreditApplicationView, LoanView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY } from '../../../testing/fixtures';
import { Bank, applicationState } from './bank';

const app = (over: Partial<CreditApplicationView> = {}): CreditApplicationView => ({
  id: 1, amount: 80000, purpose: 'Mähdrescher', termMonths: 48, status: 'PROCESSING', submittedAtGameTime: DAY,
  decisionVisibleAtGameTime: 3 * DAY, decision: null, reasonCategory: null, offeredAmount: null, offeredTermMonths: null,
  offeredInterestRatePercent: null, loanId: null, ...over,
});

const loan = (over: Partial<LoanView> = {}): LoanView => ({
  id: 4, principal: 60000, remainingAmount: 45000, interestRatePercent: 5.5, termMonths: 36, monthlyInstallment: 1812,
  purpose: 'Mähdrescher', status: 'ACTIVE', legacy: false, blocksNewCredit: false, nextDueGameTime: 10 * DAY, overdue: false,
  escalationLevel: 0, missedInstallments: 0, paidInstallments: 8, deferredUntilGameTime: null,
  history: [{ gameTime: DAY, amount: 60000, type: 'DISBURSEMENT' }, { gameTime: 2 * DAY, amount: 1812, type: 'INSTALLMENT' }],
  ...over,
});

describe('applicationState', () => {
  it('maps status and decision to the visible state', () => {
    expect(applicationState(app())).toBe('processing');
    expect(applicationState(app({ status: 'DECIDED', decision: 'COUNTER_OFFER' }))).toBe('counter');
    expect(applicationState(app({ status: 'DECIDED', decision: 'REJECTED' }))).toBe('rejected');
    expect(applicationState(app({ status: 'ACCEPTED', decision: 'APPROVED' }))).toBe('approved');
    expect(applicationState(app({ status: 'ACCEPTED', decision: 'COUNTER_OFFER' }))).toBe('accepted');
    expect(applicationState(app({ status: 'DECLINED', decision: 'COUNTER_OFFER' }))).toBe('declined');
  });
});

describe('Bank', () => {
  function setup(apps: CreditApplicationView[], loans: LoanView[] = []) {
    TestBed.configureTestingModule({
      imports: [Bank],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Bank);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/credit-applications').flush(apps);
    http.expectOne('/api/loans').flush(loans);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const btn = (id: string, i = 0) => el.querySelectorAll(`[data-testid="${id}"] button`)[i] as HTMLButtonElement;
    return { fixture, http, el, btn, store: TestBed.inject(GameStateStore), cmp: fixture.componentInstance };
  }

  it('submits a typed application and shows it as processing', () => {
    const { http, el, fixture, btn, cmp } = setup([]);
    cmp.form.setValue({ amount: 80000, purpose: 'Mähdrescher', termMonths: 48 });
    btn('credit-submit').click();
    const req = http.expectOne('/api/credit-applications');
    expect(req.request.body).toEqual({ amount: 80000, purpose: 'Mähdrescher', termMonths: 48 });
    req.flush(app());
    fixture.detectChanges();
    const row = el.querySelector('[data-testid="application"]')!;
    expect(row.getAttribute('data-state')).toBe('processing');
    expect(row.textContent).toContain('In Bearbeitung');
    expect(row.textContent).toContain('Tag 3');
  });

  it('does not submit an invalid form', () => {
    const { http, el, fixture, btn, cmp } = setup([]);
    cmp.form.setValue({ amount: 0, purpose: '', termMonths: 48 });
    btn('credit-submit').click();
    fixture.detectChanges();
    http.expectNone('/api/credit-applications');
    expect(el.querySelector('[data-testid="credit-invalid"]')).not.toBeNull();
  });

  it('shows backend validation messages', () => {
    const { http, el, fixture, btn, cmp } = setup([]);
    cmp.form.setValue({ amount: 1000, purpose: 'x', termMonths: 2 });
    btn('credit-submit').click();
    http.expectOne('/api/credit-applications').flush({ code: 'INVALID_TERM', message: 'Laufzeit muss zwischen 6 und 240 Monaten liegen.', fields: {} }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="credit-error"]')?.textContent).toContain('Laufzeit');
  });

  it('shows a rejection only with a coarse reason category, never numbers of the formula', () => {
    const { el } = setup([app({ status: 'DECIDED', decision: 'REJECTED', reasonCategory: 'INSUFFICIENT_EQUITY' })]);
    expect(el.querySelector('[data-testid="rejection-reason"]')?.textContent).toContain('Zu wenig Eigenkapital');
    expect(el.textContent).not.toMatch(/score/i);
  });

  it('accepts a counter offer and reloads loans', () => {
    const counter = app({ status: 'DECIDED', decision: 'COUNTER_OFFER', reasonCategory: 'LOAN_TOO_LARGE_FOR_FARM', offeredAmount: 60000, offeredTermMonths: 36, offeredInterestRatePercent: 5.5 });
    const { el, http, btn, fixture } = setup([counter]);
    expect(el.querySelector('[data-testid="counter-offer"]')?.textContent?.replace(/\s/g, ' ')).toContain('60.000 € über 36 Monate zu 5,5 % Zins');
    btn('counter-accept').click();
    http.expectOne('/api/credit-applications/1/accept-counter').flush({ ...counter, status: 'ACCEPTED', loanId: 4 });
    http.expectOne('/api/credit-applications').flush([{ ...counter, status: 'ACCEPTED', loanId: 4 }]);
    http.expectOne('/api/loans').flush([loan()]);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="loan"]').length).toBe(1);
    expect(el.querySelector('[data-testid="application"]')?.getAttribute('data-state')).toBe('accepted');
  });

  it('declines a counter offer', () => {
    const counter = app({ status: 'DECIDED', decision: 'COUNTER_OFFER', offeredAmount: 60000, offeredTermMonths: 36, offeredInterestRatePercent: 5 });
    const { el, http, btn, fixture } = setup([counter]);
    btn('counter-decline').click();
    http.expectOne('/api/credit-applications/1/decline-counter').flush({ ...counter, status: 'DECLINED' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="application"]')?.getAttribute('data-state')).toBe('declined');
  });

  it('lists loans with plan, overdue state and payment history', () => {
    const { el, btn, fixture } = setup([], [loan({ overdue: true, escalationLevel: 2 })]);
    expect(el.querySelector('[data-testid="loan-remaining"]')?.textContent?.replace(/\s/g, ' ')).toContain('45.000 €');
    expect(el.querySelector('[data-testid="loan-plan"]')?.textContent).toContain('8 bezahlt · 28 offen');
    expect(el.querySelector('[data-testid="loan-overdue"]')).not.toBeNull();
    expect(el.textContent).toContain('Verzugsgebühr');
    expect(el.querySelector('[data-testid="stat-debt"]')?.textContent?.replace(/\s/g, ' ')).toContain('45.000 €');
    btn('loan-history-toggle').click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="loan-history"] tbody tr').length).toBe(2);
    expect(el.querySelector('[data-testid="loan-history"]')?.textContent).toContain('Auszahlung');
  });

  it('requests a deferral and shows the result', () => {
    const { el, http, btn, fixture } = setup([], [loan()]);
    const ta = el.querySelector('[data-testid="deferral-text"]') as HTMLTextAreaElement;
    ta.value = 'Die Ernte kommt erst im Herbst.';
    ta.dispatchEvent(new Event('input'));
    btn('deferral-request').click();
    const req = http.expectOne('/api/loans/4/stundung');
    expect(req.request.body).toEqual({ message: 'Die Ernte kommt erst im Herbst.' });
    req.flush({ granted: false, reasonCategory: 'POOR_PAYMENT_HISTORY' });
    http.expectOne('/api/credit-applications').flush([]);
    http.expectOne('/api/loans').flush([loan()]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="deferral-result"]')?.textContent).toContain('Stundung abgelehnt');
    expect(el.querySelector('[data-testid="deferral-result"]')?.textContent).toContain('Schwache Zahlungshistorie');
  });

  it('reloads when game time advances (decision becomes visible)', () => {
    const { el, http, store, fixture } = setup([app()]);
    store.stateVersion.update((v) => v + 1);
    fixture.detectChanges();
    http.expectOne('/api/credit-applications').flush([app({ status: 'DECIDED', decision: 'REJECTED', reasonCategory: 'INSUFFICIENT_LIQUIDITY' })]);
    http.expectOne('/api/loans').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="application"]')?.getAttribute('data-state')).toBe('rejected');
  });

  it('highlights the application linked from a mail', () => {
    const { el, fixture } = setup([app({ id: 1 }), app({ id: 2 })]);
    fixture.componentRef.setInput('application', '2');
    fixture.detectChanges();
    const rows = el.querySelectorAll('[data-testid="application"]');
    expect(rows[1].className).toContain('border-accent');
    expect(rows[0].className).not.toContain('border-accent');
  });
});
