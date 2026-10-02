import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LoanChoicesView, MachineLoanView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { MachineLoansCard, loanEndKey } from './machine-loans-card';

const loan = (over: Partial<MachineLoanView> = {}): MachineLoanView => ({
  id: 3, kind: 'LOAN', status: 'ACTIVE', lender: { id: 4, name: 'Gerd Albers', role: 'NEIGHBOR_FARMER' } as MachineLoanView['lender'],
  vehicleName: 'CLAAS LEXION', categoryName: 'HARVESTERS', listPrice: 380000, days: 3, dailyRent: 1140, vehicleId: 'veh_00077',
  deliveredGameTime: 10 * DAY, endsGameTime: 13 * DAY, rentDaysBooked: 1, lateDays: 0, compensation: null, endReason: null,
  vehicleDealId: null, ...over,
});
const demo: LoanChoicesView = { daysMin: 1, daysMax: 2, choices: [
  { storeXmlFilename: 'data/vehicles/fendt/vario700.xml', name: 'Fendt 700 Vario', categoryName: 'TRACTORSL', listPrice: 245000, dailyRent: 0 },
  { storeXmlFilename: 'data/vehicles/deutz/series5.xml', name: 'Deutz-Fahr Serie 5', categoryName: 'TRACTORSM', listPrice: 98000, dailyRent: 0 },
] };

describe('MachineLoansCard', () => {
  function setup(loans: MachineLoanView[] = [loan()]) {
    TestBed.configureTestingModule({ imports: [MachineLoansCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(MachineLoansCard);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/machine-loans').flush(loans);
    http.expectOne('/api/machine-loans/demo').flush(demo);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('maps a late return to one end reason', () => {
    expect(loanEndKey('LATE:VEHICLE_IN_USE')).toBe('LATE');
    expect(loanEndKey('NOT_BOUGHT')).toBe('NOT_BOUGHT');
    expect(loanEndKey(null)).toBeNull();
  });

  it('lists running and closed loans with rent, late days, compensation and end reason', () => {
    const { el } = setup([loan(), loan({ id: 2, status: 'RETURNED', lateDays: 1, compensation: 9500, endReason: 'LATE:VEHICLE_IN_USE' })]);
    const items = el.querySelectorAll('[data-testid="loan"]');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('auf dem Hof');
    expect(items[0].textContent).toContain('Gerd Albers');
    expect(items[1].textContent).toContain('verspätet zurückgegeben');
    expect(items[1].textContent).toContain('1 Tage verspätet');
  });

  it('requests a demo of the chosen machine', () => {
    const { el, fixture, http } = setup([]);
    expect(el.textContent).toContain('Noch keine Leih');
    const select = el.querySelector('[data-testid="demo-choice"]') as HTMLSelectElement;
    select.value = 'data/vehicles/deutz/series5.xml';
    select.dispatchEvent(new Event('change'));
    (el.querySelector('[data-testid="demo-form"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/machine-loans/demo');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ storeXmlFilename: 'data/vehicles/deutz/series5.xml' });
    req.flush(loan({ kind: 'DEMO', status: 'DELIVERING', vehicleName: 'Deutz-Fahr Serie 5', dailyRent: 0 }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="loan-info"]')?.textContent).toContain('Deutz-Fahr Serie 5');
  });
});
