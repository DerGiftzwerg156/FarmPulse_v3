import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { StatementEntryView, StatementView } from '../../core/api/models';
import { StatementCard } from './statement-card';

const entry = (seq: number, over: Partial<StatementEntryView>): StatementEntryView => ({
  seq, gameTime: 40 * 86_400_000 + 14.5 * 3_600_000, year: 2, period: 8, day: 2, category: 'AI',
  financeClass: 'OPERATING_EXPENSE', amount: -100, count: 1, single: false, liters: null, fillType: null, sellPoint: null,
  sellPointName: null, note: null, vehicleMatch: null, vehicleNames: null, ...over,
});

const OCTOBER: StatementView = {
  available: true, year: 2, period: 8,
  months: [{ year: 2, period: 7, entries: 4 }, { year: 2, period: 8, entries: 4 }],
  entries: [
    entry(1, { category: 'AI', amount: -250, count: 3 }),
    entry(2, { category: 'SOLD_PRODUCTS', financeClass: 'OPERATING_INCOME', amount: 5200, count: 6, liters: 24000,
      fillType: 'WHEAT', sellPoint: 'MillNorth', sellPointName: 'Mühle Nord' }),
    entry(3, { category: 'SHOP_VEHICLE_BUY', financeClass: 'INVESTMENT', amount: -90000, single: true,
      vehicleMatch: 'MATCHED', vehicleNames: 'Fendt 942 Vario' }),
    entry(4, { category: 'RPSIM_SALARY_PAYMENT', amount: -2400, single: true, note: 'Gehalt Anna Berger' }),
  ],
};

describe('StatementCard', () => {
  function setup(view: StatementView) {
    TestBed.configureTestingModule({ imports: [StatementCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(StatementCard);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/finances/statement').flush(view);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement, cmp: fixture.componentInstance };
  }

  it('explains that an older mod has no single bookings', () => {
    const { el } = setup({ available: false, year: null, period: null, months: [], entries: [] });
    expect(el.querySelector('[data-testid="statement-empty"]')?.textContent).toContain('aktuelle Version des Mods');
  });

  it('lists every entry with date, details and amount and sums them up', () => {
    const { el } = setup(OCTOBER);
    const rows = [...el.querySelectorAll('[data-testid="statement-row"]')].map((r) => r.textContent ?? '');
    expect(rows.length).toBe(4);
    expect(rows[0]).toContain('2. Oktober');
    expect(rows[0]).not.toContain('14:30'); // daily sum: no time of day
    expect(rows[0]).toContain('Helferlohn');
    expect(rows[0]).toContain('3 Buchungen an diesem Tag');
    expect(rows[1]).toContain('Weizen');
    expect(rows[1]).toContain('24.000 l');
    expect(rows[1]).toContain('Mühle Nord');
    expect(rows[2]).toContain('2. Oktober, 14:30');
    expect(rows[2]).toContain('Fahrzeugkauf');
    expect(rows[2]).toContain('Fendt 942 Vario');
    expect(rows[2]).toContain('Investition');
    expect(rows[3]).toContain('Gehalt Anna Berger');
    expect(el.querySelector('[data-testid="statement-in"]')?.textContent).toContain('5.200');
    expect(el.querySelector('[data-testid="statement-out"]')?.textContent).toContain('92.650');
    expect(el.querySelector('[data-testid="statement-balance"]')?.textContent).toContain('87.450');
  });

  it('filters by category', () => {
    const { el, fixture, cmp } = setup(OCTOBER);
    cmp.category.set('SHOP_VEHICLE_BUY');
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="statement-row"]').length).toBe(1);
    expect(el.querySelector('[data-testid="statement-out"]')?.textContent).toContain('90.000');
  });

  it('marks purchases that could not be assigned to one vehicle', () => {
    const { el } = setup({ ...OCTOBER, entries: [entry(1, { category: 'SHOP_VEHICLE_BUY', financeClass: 'INVESTMENT',
      amount: -12000, single: true, vehicleMatch: 'AMBIGUOUS', vehicleNames: 'Fendt 942 Vario, Kipper' })] });
    expect(el.querySelector('[data-testid="statement-vehicle"]')?.textContent).toContain('nicht eindeutig zuordenbar');
  });

  it('loads another month when it is selected', () => {
    const { el, http } = setup(OCTOBER);
    const select = el.querySelector('[data-testid="statement-month"]') as HTMLSelectElement;
    select.value = '2-7';
    select.dispatchEvent(new Event('change'));
    const req = http.expectOne((r) => r.url === '/api/finances/statement');
    expect(req.request.params.get('year')).toBe('2');
    expect(req.request.params.get('period')).toBe('7');
  });
});
