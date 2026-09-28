import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TaxOverviewView } from '../../core/api/models';
import { TaxCard } from './tax-card';

const overview = (over: Partial<TaxOverviewView> = {}): TaxOverviewView => ({
  currentYear: 2, incomeSoFar: 60000, expenseSoFar: -20000, estimatedTax: 5000, ratePercent: 25, allowance: 20000,
  nextPrepayment: 1250, nextPrepaymentPeriod: 4, lastAssessment: null, advisorActive: false, journalAvailable: true,
  openBills: 0, ...over,
});

describe('TaxCard', () => {
  function setup(o: TaxOverviewView) {
    TestBed.configureTestingModule({
      imports: [TaxCard],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(TaxCard);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/tax').flush(o);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the estimated tax and the next prepayment', () => {
    const { el } = setup(overview());
    expect(el.querySelector('[data-testid="tax-estimated"]')?.textContent).toContain('5.000');
    expect(el.querySelector('[data-testid="tax-estimated"]')?.textContent).toContain('J2');
    expect(el.querySelector('[data-testid="tax-prepayment"]')?.textContent).toContain('1.250');
    expect(el.querySelector('[data-testid="tax-prepayment"]')?.textContent).toContain('Juni');
    expect(el.querySelector('[data-testid="tax-unavailable"]')).toBeNull();
    expect(el.querySelector('[data-testid="tax-assessment"]')).toBeNull();
  });

  it('explains the missing journal and links open bills', () => {
    const { el } = setup(overview({ journalAvailable: false, nextPrepayment: null, nextPrepaymentPeriod: null, openBills: 1 }));
    expect(el.querySelector('[data-testid="tax-unavailable"]')?.textContent).toContain('Hofbuchhaltung');
    expect(el.querySelector('[data-testid="tax-prepayment"]')?.textContent).toContain('keine');
    expect(el.querySelector('[data-testid="tax-bills-link"]')).not.toBeNull();
  });

  it('shows the traceable calculation of the last assessment', () => {
    const { el } = setup(overview({ lastAssessment: {
      taxYear: 1, months: 12, operatingIncome: 100000, operatingExpense: -40000, depreciation: 5000, interest: 1000,
      profit: 54000, allowance: 20000, taxable: 34000, ratePercent: 25, advisorReduction: 850, tax: 7650, prepayments: 8000,
      balance: -350, auditStatus: 'ANNOUNCED' } }));
    const table = el.querySelector('[data-testid="tax-assessment"]')?.textContent ?? '';
    expect(table).toContain('54.000');
    expect(table).toContain('Abzug Steuerberatung');
    expect(el.querySelector('[data-testid="tax-balance"]')?.textContent).toContain('Erstattung');
    expect(el.querySelector('[data-testid="tax-balance"]')?.textContent).toContain('350');
    expect(el.querySelector('[data-testid="tax-audit"]')?.textContent).toContain('Betriebsprüfung angekündigt');
  });

  it('requests a tax advisor offer', () => {
    const { el, http, fixture } = setup(overview());
    (el.querySelector('[data-testid="tax-advisor-request"] button') as HTMLButtonElement).click();
    http.expectOne('/api/tax/advisor/offer').flush({});
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="tax-advisor-requested"]')?.textContent).toContain('Verträge & Vorgänge');
  });

  it('shows an active advisor instead of the request button', () => {
    const { el } = setup(overview({ advisorActive: true }));
    expect(el.querySelector('[data-testid="tax-advisor-active"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="tax-advisor-request"]')).toBeNull();
  });
});
