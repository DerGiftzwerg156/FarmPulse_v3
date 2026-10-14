import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CropForecast, CropPlanningView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { CropPlanning, cropProducts, sowMonths } from './crop-planning';

const forecast = (over: Partial<CropForecast> = {}): CropForecast => ({
  fruitType: 'WHEAT', fillType: 'WHEAT', hectares: 4.5, litersPerHa: 8000, yieldSource: 'FIELD', liters: 36000,
  pricePerLiter: 0.23, priceSource: 'BEST', revenue: 8280, directCostsPerHa: 900, costSource: 'SEED', directCosts: 4050,
  margin: 4230, ...over,
});

const view = (over: Partial<CropPlanningView> = {}): CropPlanningView => ({
  year: 2, period: 6, windows: true, reminders: true, discussed: false,
  crops: [
    { name: 'WHEAT', title: 'Weizen', fillType: 'WHEAT', products: [], plantablePeriods: [6, 7], harvestablePeriods: [4, 5], litersPerSqm: 0.9 },
    { name: 'MAIZE', title: 'Mais', fillType: 'MAIZE', products: ['CHAFF'], plantablePeriods: [2, 3], harvestablePeriods: [7, 8], litersPerSqm: 0.8 },
  ],
  fields: [
    { farmlandId: 12, name: '12', hectares: 4.5, runningCrop: 'WHEAT', lastCrop: 'WHEAT', sown: true, previous: forecast(),
      plan: { id: 1, status: 'OPEN', target: 'NEXT', fruitType: 'WHEAT', fillType: 'WHEAT', sowPeriod: 9, note: 'Winterweizen',
        checks: [{ code: 'ROTATION_SAME', values: { hectares: 4.5, premium: 180 } },
          { code: 'SOW_OUTSIDE_WINDOW', values: { period: 9, periods: [6, 7] } }],
        forecast: forecast(), window: { start: 10 * DAY, end: 12 * DAY, active: true } } },
    { farmlandId: 14, name: '14', hectares: 2, runningCrop: null, lastCrop: null, sown: false, previous: null, plan: null },
  ],
  planMargin: 4230, previousMargin: 4230,
  seeds: [{ fruitType: 'WHEAT', hectares: 4.5, liters: 4500, cost: 4050 }],
  marketing: [{ fillType: 'WHEAT', expected: 36000, contracted: 18000, soldPercent: 50 }],
  feed: [{ animalType: 'COW', title: 'Silage', kind: 'FOOD', fillTypes: ['SILAGE'], yearNeed: 100000, expected: 20000,
    coveredPercent: 20, suggestCrop: 'MAIZE', suggestHectares: 10 }],
  history: [{ id: 3, farmlandId: 14, name: '14', status: 'DEVIATED', plannedCrop: 'BARLEY', actualCrop: 'WHEAT', harvestYear: 2,
    ended: true, plannedLitersPerHa: 7000, actualLitersPerHa: 7200, plannedMargin: 2000, actualMargin: 2500 }],
  ...over,
});

function page(v: CropPlanningView) {
  TestBed.configureTestingModule({ imports: [CropPlanning], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
  TestBed.inject(GameStateStore).savegame.set(savegame({}));
  const fixture = TestBed.createComponent(CropPlanning);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/crop-plans').flush(v);
  fixture.detectChanges();
  return { fixture, http, el: fixture.nativeElement as HTMLElement };
}

const row = (el: HTMLElement, id: number) => el.querySelector(`[data-field="${id}"]`) as HTMLElement;

describe('crop planner helpers', () => {
  it('offers the sowing months of the crop and its products', () => {
    expect(sowMonths(view().crops[0])).toEqual([6, 7]);
    expect(sowMonths(undefined).length).toBe(12);
    expect(cropProducts(view().crops[1])).toEqual(['MAIZE', 'CHAFF']);
    expect(cropProducts(undefined)).toEqual([]);
  });
});

describe('CropPlanning (Feldbuch → Anbauplanung, R4-A)', () => {
  it('shows the plan with its hints, forecast, window, needs and plan / actual', () => {
    const { el } = page(view());
    const r12 = row(el, 12);
    const checks = [...r12.querySelectorAll('[data-testid="cp-check"]')].map((c) => c.textContent ?? '');
    expect(checks[0]).toContain('Fruchtfolgeprämie entfällt für 4,5 ha (≈ 180 €)');
    expect(checks[1]).toContain('November liegt nicht im Saatfenster (August, September)');
    expect(r12.querySelector('[data-testid="cp-forecast"]')?.textContent).toContain('8.000 l/ha (Ø dieses Feldes)');
    expect(r12.querySelector('[data-testid="cp-window"]')?.textContent).toContain('Saatfenster jetzt');
    expect(r12.querySelector('[data-testid="cp-plan"]')?.textContent).toContain('nächste Saison');
    expect(el.querySelector('[data-testid="cp-sum"]')?.textContent).toContain('Deckungsbeitrag des Plans');
    expect(el.querySelector('[data-testid="cp-marketing"]')?.textContent).toContain('50 % vorab verkauft');
    expect(el.querySelector('[data-testid="cp-feed"]')?.textContent).toContain('gedeckt zu 20 %');
    expect(el.querySelector('[data-testid="cp-feed"]')?.textContent).toContain('etwa 10 ha Mais');
    expect(el.querySelector('[data-testid="cp-actual"]')?.textContent).toContain('abweichend');
    expect(el.querySelector('[data-testid="cp-no-windows"]')).toBeNull();
  });

  it('plans a field with crop, product, sowing month and note', () => {
    const { el, http, fixture } = page(view());
    const r14 = row(el, 14);
    const crop = r14.querySelector('[data-testid="cp-crop"]') as HTMLSelectElement;
    crop.value = 'MAIZE';
    crop.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (r14.querySelector('[data-testid="cp-product"]') as HTMLSelectElement).value = 'CHAFF';
    (r14.querySelector('[data-testid="cp-sow"]') as HTMLSelectElement).value = '3';
    (r14.querySelector('[data-testid="cp-note"]') as HTMLInputElement).value = ' Silomais ';
    (r14.querySelector('[data-testid="cp-save"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/crop-plans/fields/14');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ fruitType: 'MAIZE', fillType: 'CHAFF', sowPeriod: 3, note: 'Silomais' });
    req.flush(view());
  });

  it('takes the crop of last year, a suggestion or fallow, and removes a plan', () => {
    const { el, http, fixture } = page(view());
    const r12 = row(el, 12);
    (r12.querySelector('[data-testid="cp-suggest"] button') as HTMLButtonElement).click();
    http.expectOne('/api/crop-plans/fields/12/suggestion').flush({ fruitType: 'MAIZE', fillType: 'MAIZE', reason: 'YIELD', value: 1000 });
    fixture.detectChanges();
    expect(r12.querySelector('[data-testid="cp-suggestion"]')?.textContent).toContain('Vorschlag: Mais');
    expect((r12.querySelector('[data-testid="cp-crop"]') as HTMLSelectElement).value).toBe('MAIZE');
    (r12.querySelector('[data-testid="cp-same"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect((r12.querySelector('[data-testid="cp-crop"]') as HTMLSelectElement).value).toBe('WHEAT');
    const crop = r12.querySelector('[data-testid="cp-crop"]') as HTMLSelectElement;
    crop.value = 'BRACHE';
    crop.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(r12.querySelector('[data-testid="cp-sow"]')).toBeNull();
    (r12.querySelector('[data-testid="cp-save"] button') as HTMLButtonElement).click();
    const fallow = http.expectOne('/api/crop-plans/fields/12');
    expect(fallow.request.body).toEqual({ fruitType: 'BRACHE', fillType: null, sowPeriod: null, note: 'Winterweizen' });
    fallow.flush(view());
    fixture.detectChanges();
    (row(el, 12).querySelector('[data-testid="cp-remove"] button') as HTMLButtonElement).click();
    const remove = http.expectOne('/api/crop-plans/fields/12');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(view());
  });

  it('switches the reminder and discusses the plan once per year', () => {
    const { el, http, fixture } = page(view());
    const box = el.querySelector('[data-testid="cp-reminders"]') as HTMLInputElement;
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    const rem = http.expectOne('/api/crop-plans/reminders');
    expect(rem.request.body).toEqual({ enabled: false });
    rem.flush(view({ reminders: false }));
    fixture.detectChanges();
    (el.querySelector('[data-testid="cp-discuss"] button') as HTMLButtonElement).click();
    http.expectOne('/api/crop-plans/discuss').flush(view({ discussed: true }));
    fixture.detectChanges();
    const discuss = el.querySelector('[data-testid="cp-discuss"] button') as HTMLButtonElement;
    expect(discuss.disabled).toBe(true);
    expect(discuss.textContent).toContain('Plan besprochen');
  });

  it('tells an older mod without windows', () => {
    const { el } = page(view({ windows: false }));
    expect(el.querySelector('[data-testid="cp-no-windows"]')).not.toBeNull();
  });
});
