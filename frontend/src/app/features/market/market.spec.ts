import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { MarketEventView, PriceSeries, PriceView, StorageOverview } from '../../core/api/models';
import { DAY } from '../../../testing/fixtures';
import { Market, toChartSeries } from './market';

const storage: StorageOverview = {
  gameTime: 40 * DAY, totalValue: 51600,
  items: [
    { fillType: 'WHEAT', amount: 180000, capacity: 200000, bestPrice: 230, bestSellPoint: 'Mühle Nord', value: 41400 },
    { fillType: 'CANOLA', amount: 24000, capacity: 80000, bestPrice: 425, bestSellPoint: 'Landhandel', value: 10200 },
  ],
};
const prices: PriceView[] = [
  { sellPoint: 'MillNorth', sellPointName: 'Mühle Nord', fillType: 'WHEAT', currentPrice: 230, trend: 'CLIMBING' },
  { sellPoint: 'AgriTrade', sellPointName: 'Landhandel', fillType: 'WHEAT', currentPrice: 212 },
  { sellPoint: 'AgriTrade', sellPointName: 'Landhandel', fillType: 'CANOLA', currentPrice: 425 },
];
const history: PriceSeries[] = [
  { sellPoint: 'MillNorth', sellPointName: 'Mühle Nord', fillType: 'WHEAT', points: [{ gameTime: 20 * DAY, price: 215 }, { gameTime: 30 * DAY, price: 222 }, { gameTime: 40 * DAY, price: 230 }] },
  { sellPoint: 'AgriTrade', sellPointName: 'Landhandel', fillType: 'WHEAT', points: [{ gameTime: 20 * DAY, price: 210 }, { gameTime: 40 * DAY, price: 212 }] },
];
const offer: MarketEventView = {
  id: 12, eventType: 'SPECIAL_OFFER', status: 'OFFERED', fillType: 'WHEAT', sellPoint: 'MillNorth', peakMultiplier: null, fixedPrice: 260,
  maxQuantity: 50000, deadlineGameTime: 50 * DAY, subsidyAmount: null, startGameTime: 39 * DAY, endGameTime: null,
  character: { id: 6, name: 'Hans Meyer', role: 'COOPERATIVE', status: 'ACTIVE' }, deliveredQuantity: null, endReason: null, playerParticipation: null,
};

describe('toChartSeries', () => {
  it('keeps the color slot bound to the sell point, not to the position', () => {
    const s = toChartSeries([history[1]], ['MillNorth', 'AgriTrade']);
    expect(s[0]).toMatchObject({ label: 'Landhandel', colorIndex: 1 });
    expect(s[0].points).toEqual([{ x: 20 * DAY, y: 210 }, { x: 40 * DAY, y: 212 }]);
  });
});

describe('Market', () => {
  function setup(events: MarketEventView[] = []) {
    TestBed.configureTestingModule({
      imports: [Market],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Market);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/storage').flush(storage);
    http.expectOne('/api/prices/current').flush(prices);
    http.expectOne('/api/market-events').flush(events);
    const hist = http.expectOne((r) => r.url === '/api/prices/history');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, http, el, hist };
  }

  it('shows silo stock with value and the credit/event hint', () => {
    const { el, hist } = setup();
    hist.flush(history);
    expect(el.querySelectorAll('[data-testid="storage-item"]').length).toBe(2);
    expect(el.querySelector('[data-testid="storage-item"]')?.textContent).toContain('Weizen');
    expect(el.querySelector('[data-testid="storage-value"]')?.textContent?.replace(/\s/g, ' ')).toContain('51.600 €');
    expect(el.querySelector('[data-testid="storage-hint"]')?.textContent).toContain('Bonität');
    expect(el.querySelector('[data-testid="storage-hint"]')?.textContent).toContain('Marktereignisse');
  });

  it('shows the price trend of the game (TODO T-10)', () => {
    const { el, hist } = setup();
    hist.flush(history);
    const trends = el.querySelectorAll('[data-testid="price-trend"]');
    expect(trends.length).toBe(1);
    expect(trends[0].getAttribute('data-trend')).toBe('CLIMBING');
    expect(trends[0].getAttribute('title')).toBe('steigend');
  });

  it('defaults to the most valuable stocked fill type and lists its prices', () => {
    const { el, hist, fixture } = setup();
    expect(hist.request.params.get('fillType')).toBe('WHEAT');
    expect(hist.request.params.get('from')).toBe(String(10 * DAY));
    hist.flush(history);
    fixture.detectChanges();
    const rows = el.querySelectorAll('[data-testid="price-row"]');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Mühle Nord');
  });

  it('renders the price history chart with sample data and reloads for another period and sell point', () => {
    const { el, hist, fixture, http } = setup();
    hist.flush(history);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="history"] [data-testid="chart-line"]').length).toBe(2);
    expect(el.querySelector('[data-testid="chart-legend"]')?.textContent).toContain('Landhandel');

    (el.querySelector('[data-testid="range-0"] button') as HTMLButtonElement).click();
    const all = http.expectOne((r) => r.url === '/api/prices/history');
    expect(all.request.params.has('from')).toBe(false);
    all.flush(history);

    const sp = el.querySelector('[data-testid="history-sellpoint"]') as HTMLSelectElement;
    sp.value = 'MillNorth';
    sp.dispatchEvent(new Event('change'));
    const one = http.expectOne((r) => r.url === '/api/prices/history');
    expect(one.request.params.get('sellPoint')).toBe('MillNorth');
    one.flush([history[0]]);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="history"] [data-testid="chart-line"]').length).toBe(1);
  });

  it('accepts a special contract via participation (no price haggling)', () => {
    const { el, hist, fixture, http } = setup([offer]);
    hist.flush(history);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="contract-terms"]')?.textContent?.replace(/\s/g, ' ')).toContain('Festpreis 260 € je 1000 l für bis zu 50.000 l');
    expect(el.querySelector('[data-testid="market-event"]')?.textContent).toContain('Mühle Nord');
    (el.querySelector('[data-testid="participate-yes"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/market-events/12/participation');
    expect(req.request.body).toEqual({ participate: true });
    req.flush({ ...offer, status: 'ACTIVE', playerParticipation: true, deliveredQuantity: 0 });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="participate-yes"]')).toBeNull();
    expect(el.querySelector('[data-testid="market-event"]')?.textContent).toContain('Aktiv');
  });
});
