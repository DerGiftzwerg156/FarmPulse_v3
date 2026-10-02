import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { LoanChoicesView, MachineLoanView } from '../../core/api/models';
import { BorrowMachine, loanDays } from './borrow-machine';

const choices: LoanChoicesView = { daysMin: 1, daysMax: 5, choices: [
  { storeXmlFilename: 'data/vehicles/claas/lexion.xml', name: 'CLAAS LEXION', categoryName: 'HARVESTERS', listPrice: 380000, dailyRent: 1140 },
  { storeXmlFilename: 'data/vehicles/fendt/vario700.xml', name: 'Fendt 700 Vario', categoryName: 'TRACTORSL', listPrice: 245000, dailyRent: 735 },
] };

describe('BorrowMachine', () => {
  function setup() {
    TestBed.configureTestingModule({ imports: [BorrowMachine], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(BorrowMachine);
    fixture.componentRef.setInput('neighborId', 4);
    fixture.detectChanges();
    return { fixture, http: TestBed.inject(HttpTestingController), el: fixture.nativeElement as HTMLElement };
  }

  it('lists the loan days of the range', () => {
    expect(loanDays(choices)).toEqual([1, 2, 3, 4, 5]);
  });

  it('loads the machines on demand and borrows the chosen one for the chosen days', () => {
    const { el, fixture, http } = setup();
    (el.querySelector('[data-testid="borrow-open"] button') as HTMLButtonElement).click();
    http.expectOne('/api/machine-loans/neighbors/4').flush(choices);
    fixture.detectChanges();
    const machine = el.querySelector('[data-testid="borrow-choice"]') as HTMLSelectElement;
    machine.value = 'data/vehicles/fendt/vario700.xml';
    machine.dispatchEvent(new Event('change'));
    const days = el.querySelector('[data-testid="borrow-days"]') as HTMLSelectElement;
    days.value = '3';
    days.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="borrow-total"]')?.textContent).toContain('2.205');
    (el.querySelector('[data-testid="borrow-form"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/machine-loans/neighbors/4');
    expect(req.request.body).toEqual({ storeXmlFilename: 'data/vehicles/fendt/vario700.xml', days: 3 });
    req.flush({ id: 5, vehicleName: 'Fendt 700 Vario', days: 3 } as MachineLoanView);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="borrow-info"]')?.textContent).toContain('Fendt 700 Vario');
  });

  it('shows the refusal of the backend', () => {
    const { el, fixture, http } = setup();
    (el.querySelector('[data-testid="borrow-open"] button') as HTMLButtonElement).click();
    http.expectOne('/api/machine-loans/neighbors/4').flush(choices);
    fixture.detectChanges();
    (el.querySelector('[data-testid="borrow-form"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http.expectOne('/api/machine-loans/neighbors/4')
      .flush({ code: 'LOAN_FUNDS', message: 'Die Miete für alle Tage muss auf dem Konto sein.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="borrow-error"]')?.textContent).toContain('Miete');
  });
});
