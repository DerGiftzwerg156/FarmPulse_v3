import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DeliveryPlacesView } from '../../core/api/models';
import { DealerPlaces } from './dealer-places';

const view: DeliveryPlacesView = {
  supported: true,
  shopIsDefault: false,
  points: [
    { id: 1, seq: 1, name: 'Maschinenhalle', farmlandId: 12, day: 3, dayTimeMs: 3 * 86_400_000 + 36_000_000, valid: true, isDefault: true },
    { id: 2, seq: 2, name: null, farmlandId: 3, day: 4, dayTimeMs: 0, valid: false, isDefault: false },
  ],
};

describe('Händler – Lieferorte (RFH-O1)', () => {
  function setup(v: DeliveryPlacesView) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DealerPlaces);
    fixture.detectChanges();
    http.expectOne('/api/dealer/delivery-points').flush(v);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the shop place on top, the points with badges and the invalid hint', () => {
    const { el } = setup(view);
    expect(el.querySelector('[data-testid="shop-place"]')?.textContent).toContain('Shop-Platz des Spiels');
    expect(el.querySelector('[data-testid="shop-default"]')?.textContent).toContain('Als Standard');
    const rows = el.querySelectorAll('[data-testid="delivery-point"]');
    expect(rows[0].textContent).toContain('Maschinenhalle');
    expect(rows[0].textContent).toContain('Flurstück 12');
    expect(rows[0].textContent).toContain('festgelegt Tag 3, 10:00');
    expect(rows[0].querySelector('[data-testid="default-badge"]')?.textContent).toContain('Standard');
    expect(rows[1].querySelector('[data-testid="point-name"]')?.textContent).toContain('Lieferort 2');
    expect(rows[1].querySelector('[data-testid="unnamed-badge"]')?.textContent).toContain('neu – bitte benennen');
    expect(rows[1].querySelector('[data-testid="invalid-badge"]')?.textContent).toContain('ungültig');
    expect(rows[1].querySelector('[data-testid="invalid-text"]')?.textContent).toContain(
      'Das Flurstück gehört nicht mehr zu deinem Hof – Lieferungen gehen auf den Shop-Platz.');
    // an invalid point cannot become the default
    expect(rows[1].querySelector('[data-testid="point-default"]')).toBeNull();
  });

  it('renames, sets the default and deletes', () => {
    const { fixture, http, el } = setup(view);
    const row = () => el.querySelectorAll('[data-testid="delivery-point"]')[1];
    (row().querySelector('[data-testid="point-rename"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const input = row().querySelector('[data-testid="rename-input"]') as HTMLInputElement;
    expect(input.maxLength).toBe(40);
    input.value = 'Hof Nord';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (row().querySelector('[data-testid="rename-save"] button') as HTMLButtonElement).click();
    const put = http.expectOne('/api/dealer/delivery-points/2/name');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ name: 'Hof Nord' });
    put.flush({ ...view, points: [view.points[0], { ...view.points[1], name: 'Hof Nord' }] });
    fixture.detectChanges();
    expect(row().querySelector('[data-testid="point-name"]')?.textContent).toContain('Hof Nord');
    expect(row().querySelector('[data-testid="rename-input"]')).toBeNull();

    (el.querySelector('[data-testid="shop-default"] button') as HTMLButtonElement).click();
    const shop = http.expectOne('/api/dealer/delivery-points/shop/default');
    expect(shop.request.method).toBe('POST');
    shop.flush({ ...view, shopIsDefault: true, points: [{ ...view.points[0], isDefault: false }] });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="shop-place"] [data-testid="default-badge"]')).not.toBeNull();

    (el.querySelector('[data-testid="point-delete"] button') as HTMLButtonElement).click();
    const del = http.expectOne('/api/dealer/delivery-points/1');
    expect(del.request.method).toBe('DELETE');
    del.flush({ supported: true, shopIsDefault: true, points: [] });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="places-empty"]')?.textContent).toContain(
      'Noch keine Lieferorte. Geh im Spiel zu Fuß an die Stelle, schau in die Richtung, in die die Maschine zeigen soll, '
      + 'und drücke ‚FarmPulse: Lieferort hier festlegen‘ (Alt+L).');
  });
});
