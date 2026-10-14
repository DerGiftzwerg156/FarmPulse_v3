import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { FeedBalanceView, FeedGroup } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { FeedBalance, feedFillTypes, feedVariant } from './feed-balance';

const group = (over: Partial<FeedGroup> = {}): FeedGroup => ({
  title: 'Silage', kind: 'FOOD', productionWeight: 0.5, fillTypes: ['SILAGE'], needPerDay: 800, estimated: false,
  stock: 54000, manual: 0, trough: 1200, reachDays: 69, reachMonths: 23, emptyAt: 79 * DAY, status: 'GREEN',
  nextHarvestAt: null, shortfall: null, yearNeed: 28800, coveredPercent: 191.7, yearBalance: 26400, monthlyCost: 240,
  neighborTrade: false, plannedHarvest: null, coveredWithPlanPercent: null, yearBalanceWithPlan: null, ...over,
});

const view = (over: Partial<FeedBalanceView> = {}): FeedBalanceView => ({
  tracked: true, counted: true, measureDays: 3, okMonths: 3, warnMonths: 1, daysPerPeriod: 3,
  animals: [{ animalType: 'COW', animals: 40, groups: [
    group(),
    group({ title: 'Heu', fillTypes: ['DRYGRASS_WINDROW'], needPerDay: 400, stock: 1500, trough: 600, reachDays: 5.3, reachMonths: 1.8,
      status: 'YELLOW', neighborTrade: true, nextHarvestAt: 24 * DAY, shortfall: 2940, monthlyCost: null, coveredPercent: 14.6,
      yearBalance: -12300, plannedHarvest: 6000, coveredWithPlanPercent: 56.3, yearBalanceWithPlan: -6300 }),
    group({ title: 'STRAW', kind: 'BEDDING', fillTypes: ['STRAW'], needPerDay: 240, estimated: true, stock: 80000, manual: 80000,
      trough: 480, reachDays: 335, reachMonths: 112, status: 'GREEN' }),
  ] }],
  stables: [{ husbandryUniqueId: 'hus_00003', animalType: 'COW', count: 40, measured: true, needPerDay: 1200, troughLiters: 1800,
    troughCapacity: 20000, troughHours: 36, strawPerDay: 240, straw: 480, monthlyCost: 240, needs: [] }],
  manual: [{ id: 5, fillType: 'STRAW', liters: 76000, bales: 20, baleSize: 'ROUND_125', balesLeft: 19, note: 'Halle 2',
    enteredGameTime: 0, checkedGameTime: 0, ageDays: 11, stale: true }],
  baleSizes: { ROUND_125: 4000, SQUARE_240: 8000 },
  worst: { animalType: 'COW', title: 'Heu', kind: 'FOOD', status: 'YELLOW', reachDays: 5.3 },
  monthlyCost: 240,
  ...over,
});

function page(v: FeedBalanceView) {
  TestBed.configureTestingModule({ imports: [FeedBalance], providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
  TestBed.inject(GameStateStore).savegame.set(savegame({}));
  const fixture = TestBed.createComponent(FeedBalance);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/feed-balance').flush(v);
  fixture.detectChanges();
  return { fixture, http, el: fixture.nativeElement as HTMLElement };
}

describe('feed balance helpers', () => {
  it('maps the traffic light to badges and lists the fill types to enter', () => {
    expect(feedVariant('GREEN')).toBe('positive');
    expect(feedVariant('YELLOW')).toBe('warning');
    expect(feedVariant('RED')).toBe('negative');
    expect(feedVariant('UNUSED')).toBe('neutral');
    expect(feedFillTypes(view())).toEqual(['DRYGRASS_WINDROW', 'SILAGE', 'STRAW']);
  });
});

describe('FeedBalance (Stall → Futter, R4-F)', () => {
  it('asks to update the mod without the food values', () => {
    const { el } = page(view({ tracked: false, animals: [], stables: [] }));
    expect(el.querySelector('[data-testid="feed-untracked"]')?.textContent).toContain('neuen Mod');
    expect(el.querySelector('[data-testid="feed-group"]')).toBeNull();
  });

  it('shows need, stock, reach and traffic light per group with the shortfall and the neighbour link', () => {
    const { el } = page(view());
    const rows = el.querySelectorAll('[data-testid="feed-group"]');
    expect(rows.length).toBe(3);
    expect(rows[0].querySelector('[data-testid="feed-status"]')?.textContent).toContain('reicht');
    expect(rows[1].getAttribute('data-status')).toBe('YELLOW');
    expect(rows[1].querySelector('[data-testid="feed-reach"]')?.textContent).toContain('5,3 Tage · 1,8 Monate');
    expect(rows[1].querySelector('[data-testid="feed-shortfall"]')?.textContent).toContain('2.940 l');
    expect(rows[1].querySelector('[data-testid="feed-neighbor"]')?.getAttribute('href')).toBe('/handel');
    expect(rows[0].querySelector('[data-testid="feed-neighbor"]')).toBeNull();
    // R4-F5: with the planned harvest of the crop plan
    expect(rows[1].querySelector('[data-testid="feed-with-plan"]')?.textContent).toContain('mit geplanter Ernte (6.000 l) gedeckt zu 56 %');
    expect(rows[1].querySelector('[data-testid="feed-with-plan"]')?.textContent).toContain('Fehlmenge 6.300 l');
    expect(rows[0].querySelector('[data-testid="feed-with-plan"]')).toBeNull();
    expect(rows[2].textContent).toContain('Einstreu (Stroh)');
    expect(rows[2].querySelector('[data-testid="feed-estimated"]')).not.toBeNull();
    expect(rows[2].textContent).toContain('davon 80.000 l eingetragen');
    expect(el.querySelector('[data-testid="feed-trough"]')?.textContent).toContain('leer in 36 Stunden');
    expect(el.querySelector('[data-testid="feed-not-counted"]')).toBeNull();
  });

  it('enters bales of a size or litres and confirms or removes an entry', () => {
    const { el, http, fixture } = page(view());
    expect(el.querySelector('[data-testid="feed-stale"]')?.textContent).toContain('bitte prüfen');
    expect(el.querySelector('[data-testid="feed-manual-entry"]')?.textContent).toContain('noch 19 × Rundballen 1,25 m');
    (el.querySelector('[data-testid="feed-check"] button') as HTMLButtonElement).click();
    http.expectOne('/api/feed-balance/manual/5/check').flush(view());
    fixture.detectChanges();
    (el.querySelector('[data-testid="feed-add-filltype"]') as HTMLSelectElement).value = 'STRAW';
    (el.querySelector('[data-testid="feed-add-bales"]') as HTMLInputElement).value = '20';
    (el.querySelector('[data-testid="feed-add-size"]') as HTMLSelectElement).value = 'SQUARE_240';
    (el.querySelector('[data-testid="feed-add-note"]') as HTMLInputElement).value = ' Halle 3 ';
    (el.querySelector('[data-testid="feed-add-save"] button') as HTMLButtonElement).click();
    const bales = http.expectOne('/api/feed-balance/manual');
    expect(bales.request.body).toEqual({ fillType: 'STRAW', bales: 20, baleSize: 'SQUARE_240', liters: null, note: 'Halle 3' });
    bales.flush(view());
    fixture.detectChanges();
    (el.querySelector('[data-testid="feed-add-filltype"]') as HTMLSelectElement).value = 'SILAGE';
    (el.querySelector('[data-testid="feed-add-bales"]') as HTMLInputElement).value = '';
    (el.querySelector('[data-testid="feed-add-liters"]') as HTMLInputElement).value = '12000';
    (el.querySelector('[data-testid="feed-add-note"]') as HTMLInputElement).value = '';
    (el.querySelector('[data-testid="feed-add-save"] button') as HTMLButtonElement).click();
    const liters = http.expectOne('/api/feed-balance/manual');
    expect(liters.request.body).toEqual({ fillType: 'SILAGE', bales: null, baleSize: null, liters: 12000, note: null });
    liters.flush({ code: 'FEED_NUMBER', message: 'Bitte eine Zahl ab 0 eintragen.', fields: {} }, { status: 422, statusText: 'x' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="feed-error"]')?.textContent).toContain('Bitte eine Zahl');
    (el.querySelector('[data-testid="feed-remove"] button') as HTMLButtonElement).click();
    const remove = http.expectOne('/api/feed-balance/manual/5');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(view({ manual: [] }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="feed-manual-entry"]')).toBeNull();
  });
});
