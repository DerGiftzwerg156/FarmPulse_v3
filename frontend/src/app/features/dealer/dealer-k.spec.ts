import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CartView, CatalogItem, DeliveryEstimate, QuoteView } from '../../core/api/models';
import { TranslationService } from '../../core/i18n/translation.service';
import { DealerCart } from './dealer-cart';
import { DealerCatalog } from './dealer-catalog';
import { DealerConfigurator } from './dealer-configurator';
import { deliveryText, expressText, powerText, typeTitle } from './dealer-format';

const H = 3_600_000;
const FENDT = 'data/vehicles/fendt/vario700/vario700.xml';

function providers() {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
  return TestBed.inject(HttpTestingController);
}

const page = {
  available: true,
  page: {
    items: [
      { xmlFilename: FENDT, name: 'Fendt 700 Vario', brand: 'FENDT', brandTitle: 'Fendt', categoryName: 'TRACTORSL',
        categoryTitle: 'Große Traktoren', price: 245000, dailyUpkeep: 120, powerMin: 200, powerMax: 280, isMod: false, motorized: true },
      { xmlFilename: 'mods/x.xml', name: 'Big Trailer', brand: 'LIZARD', brandTitle: null, categoryName: 'TRAILERS',
        categoryTitle: null, price: 41000, dailyUpkeep: 20, powerMin: null, powerMax: null, isMod: true, motorized: false },
    ],
    total: 120, page: 0, pageSize: 50,
    categories: [{ name: 'TRACTORSL', title: 'Große Traktoren' }, { name: 'TRAILERS', title: null }],
    brands: [{ name: 'FENDT', title: 'Fendt' }],
  },
};

const opt = (index: number, name: string, extra: object = {}) => ({ index, saveId: null, name, desc: null, price: 0,
  dailyUpkeep: 0, isDefault: false, isSelectable: true, isYesNoOption: false, color: null, dependent: [], power: null, ...extra });

const item: CatalogItem = {
  xmlFilename: FENDT, name: 'Fendt 700 Vario', brand: 'FENDT', brandTitle: 'Fendt', categoryName: 'TRACTORSL',
  categoryTitle: 'Große Traktoren', price: 245000, dailyUpkeep: 120, powerMin: 200, powerMax: 280, isMod: false,
  configurations: [
    { name: 'baseColor', title: null, options: [opt(1, 'Grün', { color: { r: 0, g: 0.5, b: 0 } }),
      opt(2, 'Schwarz', { price: 2500, color: { r: 0, g: 0, b: 0 } })] },
    { name: 'motor', title: null, options: [opt(1, '720 Vario'), opt(2, '724 Vario', { price: 18000, dailyUpkeep: 10 }),
      opt(3, 'Gold', { isSelectable: false })] },
    { name: 'frontLoader', title: 'Frontlader laut XML', options: [opt(1, 'Nein'), opt(2, 'Ja', { isYesNoOption: true })] },
  ],
  defaultConfigurations: { baseColor: 1, motor: 1, frontLoader: 1 },
  configurationSets: [{ name: 'Profi-Paket', isDefault: false, configurations: { motor: 2 } }],
};

const delivery = (from: number, minH: number, maxH: number, eMinH: number, eMaxH: number): DeliveryEstimate => ({
  fromGameTime: from, earliestAt: (Math.floor(from / (24 * H)) + 1) * 24 * H + 9 * H,
  minAt: from + minH * H, maxAt: from + maxH * H, expressMinAt: from + eMinH * H, expressMaxAt: from + eMaxH * H,
});

const quote = (extra: Partial<QuoteView> = {}): QuoteView => ({
  xmlFilename: FENDT, name: 'Fendt 700 Vario', configurations: { baseColor: 1, motor: 1, frontLoader: 1 },
  listPrice: 245000, dailyUpkeep: 120, configurationSet: null, setWith: [], deviations: [], extraDays: 0,
  delivery: delivery(10 * 24 * H + 10 * H, 48, 96, 24, 48), ...extra,
});

describe('Händler – Katalog, Konfigurator, Warenkorb (RFH-K)', () => {
  it('formats power, type names and the delivery span like agreed', () => {
    const i18n = TestBed.inject(TranslationService);
    expect(powerText(i18n, { powerMin: 150, powerMax: 150 })).toBe('150 PS');
    expect(powerText(i18n, { powerMin: 150, powerMax: 200 })).toBe('150–200 PS');
    expect(powerText(i18n, { powerMin: null, powerMax: null })).toBe('–');
    expect(typeTitle(i18n, 'wheel', null)).toBe('Bereifung');
    expect(typeTitle(i18n, 'wheel', 'Reifen laut XML')).toBe('Reifen laut XML');
    expect(typeTitle(i18n, 'attacherJoint', null)).toBe('attacherJoint');
    const from = 10 * 24 * H + 10 * H;
    expect(deliveryText(i18n, delivery(from, 48, 96, 24, 48))).toBe('2–4 Spieltage');
    expect(expressText(i18n, delivery(from, 48, 96, 24, 48))).toBe('Express: 1–2 Spieltage');
    expect(deliveryText(i18n, delivery(from, 36, 36, 18, 18))).toBe('1,5 Spieltage');
    // under a day: hours; at 9:00 of the next game day: "frühestens morgen 9 Uhr"
    const d = delivery(from, 23, 23, 0, 0);
    expect(deliveryText(i18n, { ...d, minAt: d.earliestAt, maxAt: d.earliestAt })).toBe('frühestens morgen 9 Uhr');
    expect(deliveryText(i18n, { ...d, minAt: d.earliestAt, maxAt: from + 30 * H })).toBe('frühestens morgen 9 Uhr – 1,3 Spieltage');
    expect(deliveryText(i18n, { ...d, minAt: from + 5 * H, maxAt: from + 20 * H })).toBe('5–20 Stunden');
  });

  it('the catalog lists, filters, sorts and pages; without catalog it says when it comes', () => {
    const http = providers();
    const fixture = TestBed.createComponent(DealerCatalog);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/dealer/catalog').flush({ available: false, page: null });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="catalog-missing"]')?.textContent).toContain(
      'Der Katalog kommt mit dem nächsten Spielstart (Mod ab Version mit Fahrzeughändler).');

    fixture.componentInstance.reload(0);
    const first = http.expectOne((r) => r.url === '/api/dealer/catalog');
    expect(first.request.params.get('sort')).toBe('CATEGORY');
    first.flush(page);
    fixture.detectChanges();
    const rows = el.querySelectorAll('[data-testid="catalog-entry"]');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Große Traktoren');
    expect(rows[0].textContent).toContain('Fendt');
    expect(rows[0].querySelector('[data-testid="power"]')?.textContent).toContain('200–280 PS');
    expect(rows[1].textContent).toContain('TRAILERS');
    expect(rows[1].querySelector('[data-testid="mod-badge"]')?.textContent).toContain('Mod');
    expect((el.querySelector('[data-testid="catalog-search"]') as HTMLInputElement).placeholder).toBe('Name suchen');

    const category = el.querySelector('[data-testid="catalog-category"]') as HTMLSelectElement;
    category.value = 'TRAILERS';
    category.dispatchEvent(new Event('change'));
    const filtered = http.expectOne((r) => r.url === '/api/dealer/catalog');
    expect(filtered.request.params.get('category')).toBe('TRAILERS');
    filtered.flush({ ...page, page: { ...page.page, items: [], total: 0 } });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="catalog-empty"]')?.textContent).toContain('Kein Eintrag passt zu den Filtern.');

    (el.querySelector('[data-testid="sort-price"]') as HTMLButtonElement).click();
    const sorted = http.expectOne((r) => r.url === '/api/dealer/catalog');
    expect(sorted.request.params.get('sort')).toBe('PRICE');
    expect(sorted.request.params.get('desc')).toBe('false');
    sorted.flush(page);
    fixture.detectChanges();
    (el.querySelector('[data-testid="page-next"] button') as HTMLButtonElement).click();
    const next = http.expectOne((r) => r.url === '/api/dealer/catalog');
    expect(next.request.params.get('page')).toBe('1');
    next.flush(page);
  });

  it('the configurator shows the options, swatches, prices and delivery and puts the machine into the cart', () => {
    const http = providers();
    const fixture = TestBed.createComponent(DealerConfigurator);
    fixture.componentRef.setInput('xmlFilename', FENDT);
    let saved: CartView | null = null;
    fixture.componentInstance.saved.subscribe((c) => (saved = c));
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/dealer/catalog/item').flush(item);
    http.expectOne('/api/dealer/configure').flush(quote());
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="package"]')?.textContent).toContain('individuell');
    const types = el.querySelectorAll('[data-testid="config-type"]');
    expect(types[0].textContent).toContain('Farbe');
    expect(types[0].querySelectorAll('[data-testid="color-option"]').length).toBe(2);
    expect(types[1].textContent).toContain('Motor');
    const motorOptions = Array.from(types[1].querySelectorAll('option')).map((o) => o.textContent?.trim().replace(/\u00a0/g, ' '));
    expect(motorOptions).toEqual(['720 Vario (Standard)', '724 Vario + 18.000 € + 10 €/Tag']); // not selectable: hidden
    // yes/no options stay a selection until the game test (owner decision 2026-10-15)
    expect(types[2].textContent).toContain('Frontlader laut XML');
    expect(types[2].querySelector('select')).not.toBeNull();
    expect(el.querySelector('[data-testid="list-price"]')?.textContent).toContain('245.000');
    expect(el.querySelector('[data-testid="delivery"]')?.textContent).toContain('2–4 Spieltage');
    expect(el.querySelector('[data-testid="express"]')?.textContent).toContain('Express: 1–2 Spieltage');
    expect(el.textContent).toContain('Lieferkosten folgen mit den Lieferorten.');

    (types[0].querySelectorAll('[data-testid="color-option"]')[1] as HTMLButtonElement).click();
    const configure = http.expectOne('/api/dealer/configure');
    expect(configure.request.body).toEqual({ xmlFilename: FENDT, configurations: { baseColor: 2 } });
    configure.flush(quote({ listPrice: 247500, configurationSet: null,
      setWith: [{ type: 'wheel', title: null, index: 2, option: 'Radgewichte' }] }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="sets-also"]')?.textContent).toContain('legt auch fest: Bereifung Radgewichte');
    expect(el.querySelector('[data-testid="list-price"]')?.textContent).toContain('247.500');

    const save = el.querySelector('[data-testid="config-save"] button') as HTMLButtonElement;
    expect(save.textContent).toContain('In den Warenkorb');
    save.click();
    const add = http.expectOne('/api/dealer/cart');
    expect(add.request.method).toBe('POST');
    add.flush({ positions: [], maxPositions: 10, listPriceSum: 0 });
    expect(saved).not.toBeNull();
  });

  it('a full cart shows the message of the dealer', () => {
    const http = providers();
    const fixture = TestBed.createComponent(DealerConfigurator);
    fixture.componentRef.setInput('xmlFilename', FENDT);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/dealer/catalog/item').flush(item);
    http.expectOne('/api/dealer/configure').flush(quote());
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-testid="config-save"] button') as HTMLButtonElement).click();
    http.expectOne('/api/dealer/cart').flush({ code: 'DEALER_CART_FULL', message: 'Der Warenkorb ist voll (höchstens 10 Positionen).',
      fields: {} }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="config-error"]')?.textContent)
      .toContain('Der Warenkorb ist voll (höchstens 10 Positionen).');
  });

  it('the cart lists positions with deviations, price and delivery; change and remove', () => {
    const http = providers();
    const fixture = TestBed.createComponent(DealerCart);
    fixture.detectChanges();
    const cart: CartView = {
      positions: [
        { id: 1, xmlFilename: FENDT, name: 'Fendt 700 Vario', choice: { baseColor: 2 },
          quote: quote({ listPrice: 247500, deviations: [{ type: 'baseColor', title: 'Farbe', index: 2, option: 'Schwarz' }] }) },
        { id: 2, xmlFilename: 'mods/gone.xml', name: 'Alter Mod', choice: {}, quote: null },
      ],
      maxPositions: 2, listPriceSum: 247500,
    };
    http.expectOne('/api/dealer/cart').flush(cart);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const rows = el.querySelectorAll('[data-testid="cart-position"]');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('[data-testid="deviations"]')?.textContent).toContain('Farbe: Schwarz');
    expect(rows[0].querySelector('[data-testid="position-price"]')?.textContent).toContain('247.500');
    expect(rows[0].querySelector('[data-testid="position-delivery"]')?.textContent).toContain('2–4 Spieltage');
    expect(rows[1].querySelector('[data-testid="position-price"]')?.textContent).toContain('–');
    expect((rows[1].querySelector('[data-testid="position-change"] button') as HTMLButtonElement).disabled).toBe(true);
    expect(el.querySelector('[data-testid="cart-sum"]')?.textContent).toContain('247.500');
    expect(el.querySelector('[data-testid="cart-count"]')?.textContent).toContain('2 von 2 Positionen');
    expect(el.querySelector('[data-testid="cart-full"]')?.textContent).toContain('Der Warenkorb ist voll (höchstens 2 Positionen).');
    expect(el.querySelector('[data-testid="request-form"]')).not.toBeNull();

    (rows[1].querySelector('[data-testid="position-remove"] button') as HTMLButtonElement).click();
    const del = http.expectOne('/api/dealer/cart/2');
    expect(del.request.method).toBe('DELETE');
    del.flush({ ...cart, positions: [cart.positions[0]] });
    fixture.detectChanges();

    (el.querySelector('[data-testid="position-change"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/dealer/catalog/item').flush(item);
    const configure = http.expectOne('/api/dealer/configure');
    expect(configure.request.body.configurations).toEqual({ baseColor: 2 });
    configure.flush(quote());
    fixture.detectChanges();
    const apply = el.querySelector('[data-testid="config-save"] button') as HTMLButtonElement;
    expect(apply.textContent).toContain('Übernehmen');
    apply.click();
    const put = http.expectOne('/api/dealer/cart/1');
    expect(put.request.method).toBe('PUT');
    put.flush({ ...cart, positions: [] });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="cart-empty"]')?.textContent).toContain('Der Warenkorb ist leer.');
  });
});
