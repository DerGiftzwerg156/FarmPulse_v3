import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FieldMarginEntry, FieldMarginTotals, FieldMarginView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { savegame } from '../../../testing/fixtures';
import { MarginCard, marginBars } from './margin-card';

const totals = (margin: number | null, over: Partial<FieldMarginTotals> = {}): FieldMarginTotals => ({
  revenue: margin === null ? null : margin + 4620,
  directCosts: -4620,
  freeOfDirectCosts: margin,
  contractor: 0,
  margin,
  premiums: 900,
  rent: 0,
  overheads: -1200,
  result: margin === null ? null : margin + 900 - 1200,
  ...over,
});

function entry(over: Partial<FieldMarginEntry> = {}): FieldMarginEntry {
  return {
    entryId: 1,
    farmlandId: 12,
    fieldName: '12',
    status: 'HARVESTED',
    harvestYear: 2,
    fruitType: 'WHEAT',
    fillType: 'WHEAT',
    hectares: 4.5,
    liters: 36000,
    price: { perLiter: 0.23, source: 'HARVEST_DAY', provisional: true },
    lines: [
      { id: 7, kind: 'SEED', source: 'MEASURED', fillType: 'SEEDS', liters: 3000, helperLiters: 0, amount: -2700, corrected: false, note: null, held: false },
      { id: 8, kind: 'FERTILIZER', source: 'MEASURED', fillType: 'FERTILIZER', liters: 1000, helperLiters: 400, amount: -1920, corrected: true, note: null, held: false },
      { id: 9, kind: 'OVERHEAD', source: 'DISTRIBUTED', fillType: null, liters: null, helperLiters: null, amount: -1200, corrected: false, note: '4/1', held: false },
      { id: 10, kind: 'EXTRA_REVENUE', source: 'ENTERED', fillType: null, liters: null, helperLiters: null, amount: 500, corrected: true, note: 'Stroh', held: false },
    ],
    totals: totals(3660),
    perHectare: totals(3660 / 4.5, { directCosts: -4620 / 4.5 }),
    locked: false,
    estimated: false,
    incomplete: false,
    ...over,
  };
}

const view = (over: Partial<FieldMarginView> = {}): FieldMarginView => ({
  years: 5,
  defaultYears: 5,
  fromYear: 1,
  toYear: 2,
  currentYear: 2,
  overheads: true,
  measured: true,
  fields: [
    { farmlandId: 12, name: '12', hectares: 4.5, running: entry({ entryId: 3, status: 'RUNNING', harvestYear: null, price: { perLiter: null, source: null, provisional: false } }), entries: [entry()] },
    { farmlandId: 14, name: '14', hectares: 2, running: null, entries: [entry({ entryId: 2, farmlandId: 14, fieldName: '14', totals: totals(-800), perHectare: totals(-400) })] },
  ],
  ranking: [
    { farmlandId: 12, name: '12', marginPerHectare: 813, n: 1 },
    { farmlandId: 14, name: '14', marginPerHectare: -400, n: 1 },
  ],
  crops: [{ fruitType: 'WHEAT', fillType: 'WHEAT', revenuePerHectare: 1840, directCostsPerHectare: -1027, marginPerHectare: 207, n: 2 }],
  ...over,
});

function page(v: FieldMarginView) {
  TestBed.configureTestingModule({ imports: [MarginCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
  TestBed.inject(GameStateStore).savegame.set(savegame({}));
  const fixture = TestBed.createComponent(MarginCard);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/field-margin').flush(v);
  fixture.detectChanges();
  return { fixture, http, el: fixture.nativeElement as HTMLElement, cmp: fixture.componentInstance };
}

describe('marginBars', () => {
  it('draws positive margins up and negative ones down from the middle line', () => {
    const bars = marginBars(view(), 2, 640, 220, { left: 12, right: 12, top: 12, bottom: 24 });
    expect(bars.map((b) => b.farmlandId)).toEqual([12, 14]);
    const zero = 12 + (220 - 36) / 2;
    expect(bars[0].y + bars[0].h).toBeCloseTo(zero);
    expect(bars[1].y).toBeCloseTo(zero);
    expect(bars[0].h).toBeGreaterThan(bars[1].h);
    expect(marginBars(view(), 1, 640, 220, { left: 12, right: 12, top: 12, bottom: 24 })).toEqual([]);
  });
});

describe('MarginCard (R4-D5)', () => {
  it('shows the table field x year with the margin per hectare, the ranking and the crops', () => {
    const { el } = page(view());
    const rows = el.querySelectorAll('[data-testid="margin-row"]');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('[data-testid="margin-cell"]')?.textContent).toContain('813');
    expect(rows[1].querySelector('[data-testid="margin-cell"]')?.textContent).toContain('-400');
    const ranking = el.querySelector('[data-testid="margin-ranking"]')!.textContent!;
    expect(ranking).toContain('bestes');
    expect(ranking).toContain('schwächstes');
    expect(el.querySelector('[data-testid="margin-crops"]')?.textContent).toContain('n');
    expect(el.querySelectorAll('[data-testid="margin-bar"]').length).toBe(2);
    expect(el.querySelector('[data-testid="margin-estimated"]')).toBeNull();
  });

  it('opens the calculation of an entry with its lines and totals', () => {
    const { el, fixture } = page(view());
    (el.querySelector('[data-testid="margin-cell"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const lines = [...el.querySelectorAll('[data-testid="margin-line"]')].map((l) => l.textContent ?? '');
    expect(lines[0]).toContain('Saatgut');
    expect(lines[1]).toContain('400 l vom Helfer gekauft');
    expect(lines[1]).toContain('korrigiert');
    expect(lines[2]).toContain('verteilt');
    expect(el.querySelector('[data-testid="margin-total-margin"]')?.textContent).toContain('3.660');
    expect(el.querySelector('[data-testid="margin-price"]')?.textContent).toContain('Preis am Erntetag');
    expect(el.querySelector('[data-testid="margin-price"]')?.textContent).toContain('vorläufig');
  });

  it('corrects, resets and removes lines and adds own ones', () => {
    const { el, fixture, http } = page(view());
    (el.querySelector('[data-testid="margin-cell"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (el.querySelector('[data-testid="margin-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const input = el.querySelector('[data-testid="margin-amount-input"]') as HTMLInputElement;
    input.value = '2500';
    (el.querySelector('[data-testid="margin-amount-save"] button') as HTMLButtonElement).click();
    const correct = http.expectOne('/api/field-margin/items/7');
    expect(correct.request.body).toEqual({ liters: null, amount: 2500 });
    correct.flush(view());
    fixture.detectChanges();
    (el.querySelector('[data-testid="margin-reset"]') as HTMLButtonElement).click();
    const reset = http.expectOne('/api/field-margin/items/8');
    expect(reset.request.body).toEqual({ liters: null, amount: null });
    reset.flush(view());
    fixture.detectChanges();
    (el.querySelector('[data-testid="margin-remove"]') as HTMLButtonElement).click();
    const remove = http.expectOne('/api/field-margin/items/10');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(view());
    fixture.detectChanges();
    (el.querySelector('[data-testid="margin-add-note"]') as HTMLInputElement).value = 'Wickelfolie';
    (el.querySelector('[data-testid="margin-add-amount"]') as HTMLInputElement).value = '120';
    (el.querySelector('[data-testid="margin-add"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const add = http.expectOne('/api/field-margin/entries/1/items');
    expect(add.request.body).toEqual({ kind: 'EXTRA_COST', note: 'Wickelfolie', liters: null, amount: 120 });
    add.flush(view());
  });

  it('switches the overheads and keeps the period of the field book evaluation', () => {
    const { el, http } = page(view());
    const box = el.querySelector('[data-testid="margin-overheads"]') as HTMLInputElement;
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    const req = http.expectOne('/api/field-margin/overheads');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush(view({ overheads: false }));
    const select = el.querySelector('[data-testid="margin-years"]') as HTMLSelectElement;
    select.value = '0';
    select.dispatchEvent(new Event('change'));
    const period = http.expectOne('/api/field-book/evaluation/period');
    expect(period.request.body).toEqual({ years: 0 });
    period.flush({});
    http.expectOne('/api/field-margin').flush(view({ years: 0 }));
  });

  it('marks estimated direct costs of an older mod and enters a price per 1000 l', () => {
    const { el, fixture, http } = page(view({ measured: false }));
    expect(el.querySelector('[data-testid="margin-estimated"]')).not.toBeNull();
    (el.querySelector('[data-testid="margin-cell"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (el.querySelector('[data-testid="margin-price-input"]') as HTMLInputElement).value = '250';
    (el.querySelector('[data-testid="margin-price-save"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/field-margin/entries/1/price');
    expect(req.request.body).toEqual({ perLiter: 0.25 });
    req.flush(view());
  });

  it('shows a locked entry read-only', () => {
    const { el, fixture } = page(view({ fields: [{ farmlandId: 12, name: '12', hectares: 4.5, running: null, entries: [entry({ locked: true })] }] }));
    (el.querySelector('[data-testid="margin-cell"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="margin-locked"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="margin-edit"]')).toBeNull();
    expect(el.querySelector('[data-testid="margin-add"]')).toBeNull();
  });
});
