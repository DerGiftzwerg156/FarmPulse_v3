import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CartView, DealerDeliveryView, DealerOrdersView, DealerOrderView, DeliveryPlacesView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { savegame } from '../../../testing/fixtures';
import { DealerCart } from './dealer-cart';
import { DealerOrders } from './dealer-orders';

const DAY = 86_400_000;
const H = 3_600_000;
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ');

const places: DeliveryPlacesView = {
  supported: true, shopIsDefault: false,
  points: [
    { id: 4, seq: 1, name: 'Maschinenhalle', farmlandId: 12, day: 3, dayTimeMs: 0, valid: true, isDefault: true },
    { id: 5, seq: 2, name: null, farmlandId: 3, day: 4, dayTimeMs: 0, valid: false, isDefault: false },
  ],
};

const delivery = (extra: Partial<DealerDeliveryView> = {}): DealerDeliveryView => ({
  id: 11, status: 'OPEN', express: true, delayed: false, deliveryPointId: 4, placeName: 'Maschinenhalle', deliveryFee: 610,
  dueGameTime: null, deliveredGameTime: null, deliveredPlace: null, due: 0, rest: 0, carriesTradeIns: true, lastFailure: null,
  refund: 0, placeChangeable: false, cancellable: false, retryable: false,
  items: [{ id: 1, position: 1, name: 'Fendt 700 Vario', listPrice: 245000, expressFee: 12250, daysMin: 2, daysMax: 4,
    priceShare: null, depositShare: null, status: 'OPEN' }],
  ...extra,
});

const offer = (extra: Partial<DealerOrderView> = {}): DealerOrderView => ({
  id: 7, status: 'OFFERED', together: true, requestedGameTime: DAY, listTotal: 245000, tradeInValue: 40000, tradeInOffer: 38000,
  offerPrice: 207000, currentPrice: 207000, depositAtAgreement: 62100, validUntilGameTime: 14 * DAY + 9 * H,
  negotiation: { id: 30, status: 'OPEN', roundsUsed: 0, maxRounds: 3, lastCounterOffer: null, closesAtGameTime: 14 * DAY + 9 * H },
  agreedPrice: null, deposit: null, depositPaid: false, openAmount: 219860, machines: 'Fendt 700 Vario', returnedToCart: false,
  closedGameTime: null, tradeIns: [{ vehicleId: 'veh_7', name: 'Fendt 312', gameValue: 40000, offerValue: 38000, status: 'OPEN' }],
  deliveries: [delivery()], ...extra,
});

const view = (orders: DealerOrderView[]): DealerOrdersView => ({ orders, depositPercent: 30, retryFee: 500 });

describe('Händler – Bestellungen (RFH-B / L)', () => {
  function setup(v: DealerOrdersView) {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DealerOrders);
    fixture.detectChanges();
    http.expectOne('/api/dealer/orders').flush(v);
    http.expectOne('/api/dealer/delivery-points').flush(places);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the offer with positions, trade-in, offer price, deposit and validity', () => {
    const { el } = setup(view([offer()]));
    expect(el.querySelector('[data-testid="order-status"]')?.textContent).toContain('Angebot');
    const item = norm(el.querySelector('[data-testid="offer-item"]')?.textContent);
    expect(item).toContain('Fendt 700 Vario');
    expect(item).toContain('Listenpreis');
    expect(item).toContain('Lieferzeit 2–4 Spieltage');
    expect(item).toMatch(/Express \+ 12\.250\s€/);
    expect(norm(el.querySelector('[data-testid="offer-fee"]')?.textContent)).toMatch(/Lieferkosten 610\s€/);
    expect(norm(el.querySelector('[data-testid="offer-trade-in"]')?.textContent)).toMatch(/Inzahlungnahme − 38\.000\s€/);
    expect(el.querySelector('[data-testid="offer-price"]')?.textContent).toContain('207.000');
    expect(el.textContent).toContain('Anzahlung bei Einigung (30 %)');
    expect(el.querySelector('[data-testid="offer-valid"]')?.textContent).toContain('Gültig bis Tag 14, 09:00');
    expect(el.querySelector('[data-testid="round"]')?.textContent).toContain('Runde 1 von 3');
    const finance = el.querySelector('[data-testid="finance"]') as HTMLAnchorElement;
    expect(finance.textContent).toContain('Finanzieren');
    expect(decodeURIComponent(finance.getAttribute('href') ?? '')).toBe('/bank/antrag?amount=219860&purpose=Neumaschine: Fendt 700 Vario');
  });

  it('bids from the form, accepts at the offer price and declines', () => {
    const { fixture, http, el } = setup(view([offer()]));
    const input = el.querySelector('[data-testid="bid-amount"]') as HTMLInputElement;
    input.value = '190000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="bid"] button') as HTMLButtonElement).click();
    const bid = http.expectOne('/api/dealer/orders/7/bid');
    expect(bid.request.body).toEqual({ amount: 190000 });
    bid.flush(view([offer({ currentPrice: 195000,
      negotiation: { id: 30, status: 'OPEN', roundsUsed: 1, maxRounds: 3, lastCounterOffer: 195000, closesAtGameTime: null } })]));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="offer-price"]')?.textContent).toContain('195.000');
    expect(el.querySelector('[data-testid="round"]')?.textContent).toContain('Runde 2 von 3');

    (el.querySelector('[data-testid="accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/dealer/orders/7/accept').flush(view([offer()]));
    fixture.detectChanges();
    (el.querySelector('[data-testid="decline"] button') as HTMLButtonElement).click();
    http.expectOne('/api/dealer/orders/7/decline').flush(view([offer({ status: 'REJECTED', negotiation: null })]));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="order-status"]')?.textContent).toContain('Keine Einigung');
    (el.querySelector('[data-testid="back-to-cart"] button') as HTMLButtonElement).click();
    http.expectOne('/api/dealer/orders/7/back-to-cart')
      .flush(view([offer({ status: 'REJECTED', negotiation: null, returnedToCart: true })]));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="back-to-cart"]')).toBeNull();
  });

  it('shows the order: agreed, deposit paid, rest, date, delivery point, cancel with the refund', () => {
    const ordered = offer({ status: 'ORDERED', agreedPrice: 200000, deposit: 60000, depositPaid: true, openAmount: 152860,
      deliveries: [delivery({ status: 'PLANNED', delayed: true, dueGameTime: 18 * DAY + 14 * H, rest: 140000, due: 152860,
        placeChangeable: true, cancellable: true, refund: 60000 })] });
    const { fixture, http, el } = setup(view([ordered]));
    expect(el.querySelector('[data-testid="order-status"]')?.textContent).toContain('Bestellt');
    expect(el.querySelector('[data-testid="agreed"]')?.textContent).toContain('200.000');
    expect(el.querySelector('[data-testid="deposit-paid"]')?.textContent).toContain('60.000');
    expect(el.querySelector('[data-testid="rest"]')?.textContent).toContain('140.000');
    expect(el.querySelector('[data-testid="due"]')?.textContent).toContain('Liefertermin Tag 18, 14:00');
    expect(el.querySelector('[data-testid="express-badge"]')?.textContent).toContain('Express');
    expect(el.querySelector('[data-testid="delayed-badge"]')?.textContent).toContain('verzögert');
    const select = el.querySelector('[data-testid="delivery-place"]') as HTMLSelectElement;
    expect([...select.options].map((o) => o.textContent?.trim())).toEqual(['Shop-Platz des Spiels', 'Maschinenhalle']);
    expect(select.value).toBe('4');
    select.value = 'shop';
    select.dispatchEvent(new Event('change'));
    const put = http.expectOne('/api/dealer/deliveries/11/place');
    expect(put.request.body).toEqual({ deliveryPointId: null });
    put.flush(view([ordered]));
    fixture.detectChanges();

    (el.querySelector('[data-testid="cancel"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(norm(el.querySelector('[data-testid="cancel-confirm"]')?.textContent))
      .toMatch(/Bestellung wirklich stornieren\? Du bekommst 60\.000\s€ der Anzahlung zurück\./);
    (el.querySelector('[data-testid="cancel-yes"] button') as HTMLButtonElement).click();
    http.expectOne('/api/dealer/deliveries/11/cancel').flush(view([{ ...ordered, status: 'CANCELLED',
      deliveries: [delivery({ status: 'CANCELLED' })] }]));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="order-status"]')?.textContent).toContain('Storniert');
  });

  it('a delivery waiting at the dealer shows the reason and the retry with 500 €', () => {
    const waiting = offer({ status: 'ORDERED', agreedPrice: 200000, deposit: 60000, depositPaid: true,
      deliveries: [delivery({ status: 'WAITING', dueGameTime: 18 * DAY, lastFailure: 'Fendt 312 ist angekoppelt', retryable: true,
        cancellable: true })] });
    const { http, el } = setup(view([waiting]));
    expect(el.querySelector('[data-testid="waiting-badge"]')?.textContent).toContain('Wartet beim Händler');
    expect(el.querySelector('[data-testid="last-failure"]')?.textContent).toContain('Letzter Versuch: Fendt 312 ist angekoppelt');
    expect(norm(el.querySelector('[data-testid="retry"]')?.textContent)).toMatch(/Lieferung erneut versuchen \(\+ 500\s€\)/);
    expect(el.textContent).toContain('Sonst neuer Versuch am nächsten Spieltag.');
    (el.querySelector('[data-testid="retry"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/dealer/deliveries/11/retry').request.method).toBe('POST');
  });

  it('a delivered order and the empty list', () => {
    const done = offer({ status: 'COMPLETED', agreedPrice: 200000, deposit: 60000, depositPaid: true,
      deliveries: [delivery({ status: 'DELIVERED', deliveredGameTime: 18 * DAY + 14 * H, deliveredPlace: 'Maschinenhalle' })] });
    let { el } = setup(view([done]));
    expect(el.querySelector('[data-testid="order-status"]')?.textContent).toContain('Geliefert');
    expect(el.querySelector('[data-testid="delivered"]')?.textContent).toContain('Geliefert Tag 18, 14:00 – Maschinenhalle');
    TestBed.resetTestingModule();
    ({ el } = setup(view([])));
    expect(el.querySelector('[data-testid="orders-empty"]')?.textContent).toContain('Noch keine Anfragen oder Bestellungen.');
  });
});

describe('Händler – Anfrage im Warenkorb (RFH-B1)', () => {
  const cart: CartView = {
    positions: [
      { id: 1, xmlFilename: 'f.xml', name: 'Fendt 700 Vario', choice: {}, quote: null },
      { id: 2, xmlFilename: 'c.xml', name: 'Amazone Catros', choice: {}, quote: null },
    ],
    maxPositions: 10, listPriceSum: 0,
  };

  function setup() {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DealerCart);
    fixture.detectChanges();
    const t = 10 * DAY;
    const quote = { xmlFilename: 'f.xml', name: 'x', configurations: {}, listPrice: 1000, dailyUpkeep: 0, configurationSet: null,
      setWith: [], deviations: [], extraDays: 0,
      delivery: { fromGameTime: t, earliestAt: t, minAt: t + 2 * DAY, maxAt: t + 4 * DAY, expressMinAt: t + DAY, expressMaxAt: t + 2 * DAY } };
    const quoted = { ...cart, positions: cart.positions.map((p) => ({ ...p, quote })) };
    http.expectOne('/api/dealer/cart').flush(quoted);
    fixture.detectChanges();
    http.expectOne('/api/dealer/trade-in-candidates').flush([{ uniqueId: 'veh_7', name: 'Fendt 312', value: 42000 }]);
    http.expectOne('/api/dealer/delivery-points').flush(places);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('asks for an offer "zusammen" with trade-in, the default delivery point and express', () => {
    const { fixture, http, el } = setup();
    expect(norm(el.querySelector('[data-testid="trade-in"]')?.textContent)).toMatch(/In Zahlung geben\s*Fendt 312\s*Spielwert 42\.000\s€/);
    expect(el.textContent).toContain('zusammen liefern');
    expect(el.textContent).toContain('einzeln liefern');
    expect(el.textContent).toContain('Express (halbe Lieferzeit, + 5 % vom Listenpreis)');
    const select = el.querySelector('[data-testid="place"]') as HTMLSelectElement;
    expect([...select.options].map((o) => o.textContent?.trim())).toEqual(['Shop-Platz des Spiels', 'Maschinenhalle']);
    expect(select.value).toBe('4');
    (el.querySelector('[data-testid="trade-in-check"]') as HTMLInputElement).click();
    const express = el.querySelector('[data-testid="express"]') as HTMLInputElement;
    express.checked = true;
    express.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="request-offer"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/dealer/orders');
    expect(req.request.body).toEqual({ together: true, tradeIns: ['veh_7'], all: { deliveryPointId: 4, express: true },
      perPosition: null });
    req.flush({ orders: [], depositPercent: 30, retryFee: 500 });
    http.expectOne('/api/dealer/cart').flush({ ...cart, positions: [] });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="requested"]')?.textContent).toContain('Angefragt – der Händler antwortet am nächsten Spieltag.');
    expect(el.querySelector('[data-testid="request-form"]')).toBeNull();
  });

  it('"einzeln liefern" sends a choice per position; the refusal of the dealer is shown', () => {
    const { fixture, http, el } = setup();
    (el.querySelector('[data-testid="separately"]') as HTMLInputElement).click();
    fixture.detectChanges();
    const selects = el.querySelectorAll('[data-testid="place"]');
    expect(selects.length).toBe(2);
    (selects[1] as HTMLSelectElement).value = 'shop';
    selects[1].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="request-offer"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/dealer/orders');
    expect(req.request.body).toEqual({ together: false, tradeIns: [], all: null,
      perPosition: { 1: { deliveryPointId: 4, express: false }, 2: { deliveryPointId: null, express: false } } });
    req.flush({ code: 'DEALER_TRADE_IN_TOO_HIGH',
      message: 'Die Inzahlungsmaschinen sind mehr wert als die Bestellung – bitte weniger in Zahlung geben.', fields: {} },
    { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="request-error"]')?.textContent)
      .toContain('Die Inzahlungsmaschinen sind mehr wert als die Bestellung – bitte weniger in Zahlung geben.');
  });
});
