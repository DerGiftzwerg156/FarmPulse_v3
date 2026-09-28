import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FinanceMonthView, FinanceOverview } from '../../core/api/models';
import { SERIES_COLORS } from '../../shared/ui/chart-wrapper';
import { FinanceCard, OTHER_COLOR, categoryColors, niceStep, operatingLines } from './finance-card';

const month = (period: number, lines: [string, number, FinanceMonthView['lines'][number]['financeClass']][],
  complete = true): FinanceMonthView => {
  const sum = (c: string) => lines.filter((l) => l[2] === c).reduce((s, l) => s + l[1], 0);
  return {
    year: 2, period, complete, operatingIncome: sum('OPERATING_INCOME'), operatingExpenses: sum('OPERATING_EXPENSE'),
    operatingResult: sum('OPERATING_INCOME') + sum('OPERATING_EXPENSE'), investment: sum('INVESTMENT'),
    divestment: sum('DIVESTMENT'), financing: sum('FINANCING'), ignored: sum('IGNORE'),
    lines: lines.map(([category, amount, financeClass]) => ({ category, amount, financeClass })),
  };
};

const JOURNAL: FinanceOverview = {
  available: true,
  months: [
    month(7, [['HARVEST_INCOME', 48000, 'OPERATING_INCOME'], ['PURCHASE_FUEL', -3000, 'OPERATING_EXPENSE'],
      ['SHOP_PROPERTY_BUY', -90000, 'INVESTMENT'], ['RPSIM_CREDIT_INSTALLMENT', -1800, 'FINANCING']]),
    month(8, [['SOLD_PRODUCTS', 1000, 'OPERATING_INCOME'], ['AI', -2500, 'OPERATING_EXPENSE']], false),
  ],
};

describe('categoryColors', () => {
  it('gives the largest categories the palette in alphabetical order and folds the rest', () => {
    const lines: [string, number, 'OPERATING_EXPENSE'][] = ['A', 'B', 'C', 'D', 'E', 'F', 'G']
      .map((c, i) => [c, -(i + 1) * 100, 'OPERATING_EXPENSE']);
    const colors = categoryColors([month(1, lines)]);
    expect([...colors.keys()]).toEqual(['C', 'D', 'E', 'F', 'G']);
    expect(colors.get('C')).toBe(SERIES_COLORS[0]);
    expect(colors.has('A')).toBe(false);
  });

  it('ignores investments and financing (they are no bars)', () => {
    expect(operatingLines(JOURNAL.months[0]).map((l) => l.category)).toEqual(['HARVEST_INCOME', 'PURCHASE_FUEL']);
  });
});

describe('niceStep', () => {
  it('rounds the axis step to 1, 2 or 5 times a power of ten', () => {
    expect(niceStep(83_000)).toBe(20_000);
    expect(niceStep(30_000)).toBe(10_000);
    expect(niceStep(7_000)).toBe(2_000);
    expect(niceStep(0)).toBe(0.2);
  });
});

describe('FinanceCard', () => {
  function setup(overview: FinanceOverview) {
    TestBed.configureTestingModule({ imports: [FinanceCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(FinanceCard);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/finances').flush(overview);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, cmp: fixture.componentInstance };
  }

  it('explains that an older mod has no journal', () => {
    const { el } = setup({ available: false, months: [] });
    expect(el.querySelector('[data-testid="finance-unavailable"]')?.textContent).toContain('aktuelle Version des Mods');
    expect(el.querySelector('[data-testid="finance-chart"]')).toBeNull();
  });

  it('shows the last complete month and stacked operating columns with the result tick', () => {
    const { el } = setup(JOURNAL);
    expect(el.textContent).toContain('Letzter abgeschlossener Monat: September J2');
    expect(el.querySelector('[data-testid="finance-result"]')?.textContent).toContain('45.000');
    // month 7: income + expense, month 8: income + expense - investment and installment are not drawn
    expect(el.querySelectorAll('[data-testid="finance-segment"]').length).toBe(4);
    expect(el.querySelectorAll('[data-testid="finance-result-tick"]').length).toBe(2);
    expect(el.querySelector('[data-testid="finance-legend"]')?.textContent).toContain('Ernteverkauf');
    expect(el.querySelector('[data-testid="finance-legend"]')?.textContent).toContain('Monatsergebnis');
  });

  it('shows a tooltip per column on hover', () => {
    const { el, fixture } = setup(JOURNAL);
    (el.querySelectorAll('[data-testid="finance-hit"]')[1] as SVGRectElement).dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    const tip = el.querySelector('[data-testid="finance-tooltip"]')!.textContent!;
    expect(tip).toContain('Oktober J2');
    expect(tip).toContain('läuft noch');
    expect(tip).toContain('Helferlohn');
    expect(tip).toContain('-1.500 €');
  });

  it('lists every class and category in the table view, unknown categories by their raw name', () => {
    const { el, fixture } = setup({ available: true,
      months: [month(7, [['MY_MOD_TYPE', 50, 'OPERATING_INCOME'], ['SHOP_PROPERTY_BUY', -90000, 'INVESTMENT']])] });
    (el.querySelector('[data-testid="finance-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="finance-row"]').length).toBe(1);
    const lines = [...el.querySelectorAll('[data-testid="finance-line"]')].map((r) => r.textContent ?? '');
    expect(lines[0]).toContain('MY_MOD_TYPE');
    expect(lines[1]).toContain('Kauf von Gebäuden');
    expect(lines[1]).toContain('Investition');
  });

  it('folds categories beyond the palette into "Sonstige"', () => {
    const lines: [string, number, 'OPERATING_EXPENSE'][] = ['A', 'B', 'C', 'D', 'E', 'F']
      .map((c, i) => [c, -(i + 1) * 100, 'OPERATING_EXPENSE']);
    const { el, cmp } = setup({ available: true, months: [month(7, lines)] });
    expect(el.querySelector('[data-testid="finance-legend"]')?.textContent).toContain('Sonstige');
    expect(cmp.columns()[0].segments.map((s) => s.color)).toContain(OTHER_COLOR);
    expect(cmp.columns()[0].segments.length).toBe(6);
  });
});
