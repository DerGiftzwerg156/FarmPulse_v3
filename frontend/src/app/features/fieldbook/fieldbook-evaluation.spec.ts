import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  FieldBookEvaluationCell,
  FieldBookEvaluationView,
  FieldBookView,
} from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { SERIES_COLORS } from '../../shared/ui/chart-wrapper';
import { savegame } from '../../../testing/fixtures';
import { FieldBook } from './fieldbook';
import { OTHER_COLOR, cropColors, cropKey, niceCeil } from './fieldbook-evaluation';

const providers = [provideHttpClient(), provideHttpClientTesting()];

function cell(over: Partial<FieldBookEvaluationCell> = {}): FieldBookEvaluationCell {
  return {
    entryId: 1,
    harvestYear: 9,
    status: 'HARVESTED',
    fruitType: 'WHEAT',
    fillType: 'WHEAT',
    liters: 36000,
    hectares: 4,
    litersPerHectare: 9000,
    measures: {
      FERT1: true,
      FERT2: true,
      LIMED: true,
      ROLLED: false,
      WEEDS: false,
      MULCHED: false,
    },
    ...over,
  };
}

const evaluation = (over: Partial<FieldBookEvaluationView> = {}): FieldBookEvaluationView => ({
  years: 5,
  defaultYears: 5,
  fromYear: 6,
  toYear: 10,
  yearsAvailable: 5,
  showLime: true,
  showWeeds: false,
  fields: [
    {
      farmlandId: 12,
      name: 'Feld 12',
      cells: [
        cell(),
        cell({
          entryId: 2,
          harvestYear: 10,
          fruitType: 'MAIZE',
          fillType: 'CHAFF',
          liters: 200000,
          litersPerHectare: 50000,
        }),
      ],
    },
    {
      farmlandId: 20,
      name: 'Feld 20',
      cells: [
        cell({
          entryId: 3,
          harvestYear: 8,
          status: 'NO_HARVEST',
          fruitType: null,
          fillType: null,
          liters: null,
          litersPerHectare: null,
        }),
      ],
    },
  ],
  comparisons: [
    {
      fruitType: 'WHEAT',
      fillType: 'WHEAT',
      measures: [
        { measure: 'FERT1', withAvg: 9000, withN: 1, withoutAvg: null, withoutN: 0 },
        { measure: 'LIMED', withAvg: 8500, withN: 2, withoutAvg: 7000, withoutN: 1 },
      ],
    },
  ],
  ...over,
});

const book: FieldBookView = {
  fieldsTracked: true,
  currentYear: 10,
  closedYears: [],
  years: [10, 9],
  showLime: true,
  showWeeds: false,
  cropsFromMap: true,
  crops: [
    {
      name: 'MAIZE',
      title: 'Mais',
      fillType: 'MAIZE',
      products: ['CHAFF'],
      needsRolling: true,
    },
  ],
  fields: [],
  notices: [],
};

function page(v: FieldBookEvaluationView) {
  TestBed.configureTestingModule({ imports: [FieldBook], providers });
  TestBed.inject(GameStateStore).savegame.set(savegame({}));
  const fixture = TestBed.createComponent(FieldBook);
  fixture.componentRef.setInput('tab', 'auswertung');
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/field-book').flush(book);
  fixture.detectChanges();
  http.expectOne('/api/field-book/evaluation').flush(v);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, http };
}

describe('FieldBookEvaluation (R33-E)', () => {
  it('shows the period, the table field x year with l/ha and the measures of the savegame', () => {
    const { el } = page(evaluation());
    expect(el.querySelector('[data-testid="fb-years"]')).toBeNull();
    const select = el.querySelector('[data-testid="fbe-years"]') as HTMLSelectElement;
    expect(select.value).toBe('5');
    expect(Array.from(select.options).map((o) => o.textContent?.trim())).toContain('Alle Jahre');
    expect(el.querySelector('[data-testid="fbe-range"]')?.textContent).toContain('6 bis 10');
    const rows = el.querySelectorAll('[data-testid="fbe-row"]');
    expect(rows.length).toBe(2);
    // a sold field keeps its name; "ohne Ernte" is marked
    expect(rows[1].textContent).toContain('Feld 20');
    expect(rows[1].textContent).toContain('ohne Ernte');
    expect(rows[0].querySelector('[data-testid="fbe-per-ha"]')?.textContent).toContain('9.000');
    expect(rows[0].textContent).toContain('Mais (Häckselgut)');
    // weeds are off: no weed sign; lime is on
    const signs = rows[0].querySelector('[data-testid="fbe-measures"]')!.textContent!;
    expect(signs).toContain('K');
    expect(signs).not.toContain('U');
  });

  it('keeps the chosen period on the server', () => {
    const { el, http, fixture } = page(evaluation());
    const select = el.querySelector('[data-testid="fbe-years"]') as HTMLSelectElement;
    select.value = '0';
    select.dispatchEvent(new Event('change'));
    const req = http.expectOne('/api/field-book/evaluation/period');
    expect(req.request.body).toEqual({ years: 0 });
    req.flush(evaluation({ years: 0, fromYear: 1 }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="fbe-range"]')?.textContent).toContain('1 bis 10');
  });

  it('draws one bar per harvest year of the chosen field, coloured by crop, with a tooltip', () => {
    const { el, fixture } = page(evaluation());
    const bars = el.querySelectorAll('[data-testid="fbe-bar"]');
    expect(bars.length).toBe(2);
    expect(bars[0].getAttribute('fill')).not.toBe(bars[1].getAttribute('fill'));
    expect(el.querySelector('[data-testid="fbe-legend"]')?.textContent).toContain('Weizen');
    // year 9 is the 4th band of 6..10
    (el.querySelectorAll('[data-testid="fbe-hit"]')[3] as SVGRectElement).dispatchEvent(
      new Event('mouseenter'),
    );
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="fbe-tooltip"]')?.textContent).toContain('9.000 l/ha');
    // a field without litres has no bars
    const field = el.querySelector('[data-testid="fbe-field"]') as HTMLSelectElement;
    field.value = '20';
    field.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="fbe-bar"]').length).toBe(0);
    expect(el.querySelector('[data-testid="fbe-no-bars"]')).not.toBeNull();
  });

  it('compares with and without each measure per crop, always with n', () => {
    const { el } = page(evaluation());
    expect(el.querySelector('[data-testid="fbe-compare-crop"]')?.textContent).toContain('Weizen');
    const rows = el.querySelectorAll('[data-testid="fbe-compare-row"]');
    expect(rows[1].textContent).toContain('Kalk');
    expect(rows[1].textContent).toContain('8.500 l/ha');
    expect(rows[1].textContent).toContain('n = 2');
    expect(rows[1].textContent).toContain('7.000 l/ha');
    expect(rows[1].textContent).toContain('n = 1');
    // n = 0: no average
    expect(rows[0].textContent).toContain('–');
    expect(rows[0].textContent).toContain('n = 0');
  });

  it('says so when the period has no finished entries', () => {
    const { el } = page(evaluation({ fields: [], comparisons: [] }));
    expect(el.querySelector('[data-testid="fbe-empty"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="fbe-table"]')).toBeNull();
  });
});

describe('field book evaluation helpers', () => {
  it('gives each crop and product a fixed palette colour and folds the rest into "Sonstige"', () => {
    const many = ['A', 'B', 'C', 'D', 'E', 'F', 'G'].map((name, i) =>
      cell({ entryId: i, fruitType: name, fillType: name }),
    );
    const colors = cropColors(evaluation({ fields: [{ farmlandId: 1, name: '1', cells: many }] }));
    expect(colors.get(cropKey('A', 'A'))).toBe(SERIES_COLORS[0]);
    expect(colors.size).toBe(SERIES_COLORS.length);
    expect(colors.get(cropKey('G', 'G')) ?? OTHER_COLOR).toBe(OTHER_COLOR);
  });

  it('shows the folded crops once as "Sonstige" in the legend', () => {
    const many = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'].map((name, i) =>
      cell({ entryId: i, fruitType: name, fillType: name }),
    );
    const { el } = page(evaluation({ fields: [{ farmlandId: 1, name: '1', cells: many }] }));
    const items = el.querySelectorAll('[data-testid="fbe-legend"] li');
    expect(items.length).toBe(SERIES_COLORS.length + 1);
    expect(items[items.length - 1].textContent).toContain('Sonstige');
  });

  it('rounds the axis maximum up to 1, 2 or 5 times a power of ten', () => {
    expect(niceCeil(9000)).toBe(10000);
    expect(niceCeil(50000)).toBe(50000);
    expect(niceCeil(1200)).toBe(2000);
  });
});
