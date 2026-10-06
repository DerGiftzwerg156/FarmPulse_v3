import { TestBed } from '@angular/core/testing';
import { FieldMapView, MapFieldView } from '../../core/api/models';
import { FieldMap, PHASE_COLORS } from './field-map';

const square = [{ x: -100, z: -50 }, { x: 100, z: -50 }, { x: 100, z: 150 }, { x: -100, z: 150 }];
const field = (over: Partial<MapFieldView>): MapFieldView => ({
  farmlandId: 1, name: '1', points: square, kind: 'OWN', ownerName: null, leased: false, leasedOut: false, fruitType: null,
  phase: null, orders: [], auction: false, hints: [], ...over,
});
const map: FieldMapView = { mapSize: 2048, fields: [
  field({ farmlandId: 1, name: '1', phase: 'GROWING', fruitType: 'WHEAT', hints: ['WEEDS'] }),
  field({ farmlandId: 2, name: '2', phase: 'HARVESTABLE', leased: true, orders: ['CONTRACTOR_WORK'] }),
  field({ farmlandId: 3, name: '3', leasedOut: true }),
  field({ farmlandId: 4, name: '4', kind: 'NEIGHBOR', ownerName: 'Hauke Harms', auction: true }),
  field({ farmlandId: 5, name: '5', kind: 'FREE' }),
] };

/** Roadmap V3.1 R31-K1: the SVG map of the Flurkarte (owner decisions 2026-10-06). */
describe('FieldMap (R31-K1)', () => {
  function setup(selected: number | null = null) {
    TestBed.configureTestingModule({ imports: [FieldMap] });
    const fixture = TestBed.createComponent(FieldMap);
    fixture.componentRef.setInput('map', map);
    fixture.componentRef.setInput('selected', selected);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const g = (id: number) => el.querySelector(`[data-farmland="${id}"]`) as SVGGElement;
    return { fixture, el, g };
  }

  it('draws the real outlines in world coordinates centred on the map, z downwards', () => {
    const { el, g } = setup();
    expect(el.querySelector('svg')?.getAttribute('viewBox')).toBe('-1024 -1024 2048 2048');
    expect(g(1).querySelector('polygon')?.getAttribute('points')).toBe('-100,-50 100,-50 100,150 -100,150');
  });

  it('colours own fields by phase, hatches leased, borders leased-out and keeps neighbours pale with the name', () => {
    const { g } = setup();
    expect(g(1).querySelector('polygon')?.getAttribute('fill')).toBe(PHASE_COLORS['GROWING']);
    expect(g(2).querySelector('polygon')?.getAttribute('fill')).toBe(PHASE_COLORS['HARVESTABLE']);
    expect(g(2).querySelector('[data-testid="map-hatch"]')).not.toBeNull();
    expect(g(1).querySelector('[data-testid="map-hatch"]')).toBeNull();
    expect(Number(g(3).querySelector('polygon')?.getAttribute('stroke-width'))).toBeGreaterThan(
      Number(g(1).querySelector('polygon')?.getAttribute('stroke-width')));
    expect(g(4).querySelector('polygon')?.getAttribute('fill-opacity')).toBe('0.15');
    expect(g(4).querySelector('[data-testid="map-owner"]')?.textContent).toContain('Hauke Harms');
    expect(g(5).querySelector('polygon')?.getAttribute('stroke-dasharray')).not.toBeNull();
    expect(g(5).querySelector('[data-testid="map-owner"]')).toBeNull();
  });

  it('shows the symbols with a tooltip and selects a field on a click', () => {
    const { fixture, g } = setup(2);
    const symbols = (id: number) => Array.from(g(id).querySelectorAll('[data-testid="map-symbol"]'))
      .map((s) => s.getAttribute('data-symbol'));
    expect(symbols(1)).toEqual(['HINT']);
    expect(symbols(2)).toEqual(['ORDER']);
    expect(symbols(4)).toEqual(['AUCTION']);
    expect(g(1).querySelector('title')?.textContent).toContain('Viel Unkraut');
    expect(g(2).querySelector('title')?.textContent).toContain('Lohnunternehmer');
    expect(g(2).querySelector('polygon')?.getAttribute('stroke')).toBe('#E2ECE9'); // selected
    let picked: number | null = null;
    fixture.componentInstance.fieldSelected.subscribe((id) => (picked = id));
    g(4).dispatchEvent(new Event('click'));
    expect(picked).toBe(4);
  });
});
