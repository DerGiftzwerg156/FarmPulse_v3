import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AnimalTradeView, CaseView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { savegame } from '../../../testing/fixtures';
import { AnimalTrade, maxCount, stablesFor, subTypesFor } from './animal-trade';

const view = (over: Partial<AnimalTradeView> = {}): AnimalTradeView => ({
  tracked: true, countMin: 1, countMax: 10, cases: [],
  stables: [
    { husbandryUniqueId: 'hus_00003', type: 'COW', count: 24, freeSlots: 6, valuePerAnimal: 4000,
      subTypes: [{ name: 'COW_HOLSTEIN', count: 20 }, { name: 'COW_ANGUS', count: 4 }], supportedSubTypes: ['COW_ANGUS', 'COW_HOLSTEIN', 'COW_SWISS_BROWN'] },
    { husbandryUniqueId: 'hus_00009', type: 'SHEEP', count: 0, freeSlots: 30, valuePerAnimal: null, subTypes: [], supportedSubTypes: ['SHEEP_LANDRACE'] },
  ],
  neighbors: [
    { id: 4, name: 'Gerd Albers', role: 'DAIRY', trustLevel: 'GOOD', animals: [{ type: 'COW', count: 35, sellUnitPrice: 4200, buyUnitPrice: 3800 }] },
    { id: 5, name: 'Jana Wulf', role: 'ARABLE', trustLevel: 'NEUTRAL', animals: [] },
  ],
  ...over,
});

describe('AnimalTrade', () => {
  function setup(v: AnimalTradeView = view()) {
    TestBed.configureTestingModule({ imports: [AnimalTrade], providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(AnimalTrade);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/trade/animals').flush(v);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('matches stables, subtypes and the deal size', () => {
    const v = view();
    const n = v.neighbors[0];
    expect(stablesFor(n, v).map((s) => s.husbandryUniqueId)).toEqual(['hus_00003']);
    const cows = v.stables[0];
    expect(subTypesFor(cows, 'BUY')).toEqual(['COW_ANGUS', 'COW_HOLSTEIN', 'COW_SWISS_BROWN']);
    expect(subTypesFor(cows, 'SELL')).toEqual(['COW_HOLSTEIN', 'COW_ANGUS']);
    expect(maxCount(n, v, cows, 'BUY', 'COW_ANGUS')).toBe(6); // free places
    expect(maxCount(n, v, cows, 'SELL', 'COW_ANGUS')).toBe(4); // own animals
    expect(maxCount(n, v, cows, 'SELL', 'COW_HOLSTEIN')).toBe(10); // deal size
  });

  it('shows only neighbours with animals, with their prices', () => {
    const { el } = setup();
    const items = el.querySelectorAll('[data-testid="animal-neighbor"]');
    expect(items.length).toBe(1);
    expect(items[0].querySelector('[data-testid="animal-stock"]')?.textContent).toContain('35');
  });

  it('offers own animals to the neighbour', () => {
    const { el, fixture, http } = setup();
    let changed = 0;
    fixture.componentInstance.changed.subscribe(() => changed++);
    const direction = el.querySelector('[data-testid="animal-direction"]') as HTMLSelectElement;
    direction.value = 'SELL';
    direction.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const subType = el.querySelector('[data-testid="animal-subtype"]') as HTMLSelectElement;
    expect(Array.from(subType.options).map((o) => o.value)).toEqual(['COW_HOLSTEIN', 'COW_ANGUS']);
    const count = el.querySelector('[data-testid="animal-count"]') as HTMLInputElement;
    count.value = '3';
    count.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="animal-form"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/trade/neighbors/4/animals/offer');
    expect(req.request.body).toEqual({ husbandryUniqueId: 'hus_00003', subType: 'COW_HOLSTEIN', count: 3 });
    req.flush({ id: 51, kind: 'ANIMAL_REQUEST' } as CaseView);
    http.expectOne('/api/trade/animals').flush(view());
    fixture.detectChanges();
    expect(changed).toBe(1);
    expect(el.querySelector('[data-testid="animal-info"]')?.textContent).toContain('Gerd Albers');
  });

  it('explains when the mod reports no stables', () => {
    const { el } = setup(view({ tracked: false, stables: [] }));
    expect(el.querySelector('[data-testid="no-stables"]')).not.toBeNull();
  });
});
