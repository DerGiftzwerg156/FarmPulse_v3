import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CaseView, TradeView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { Trade, requestables } from './trade';

const view = (over: Partial<TradeView> = {}): TradeView => ({
  silosTracked: true, fieldsTracked: true, missionLimitReached: false,
  silos: [{ fillType: 'STRAW', amount: 1000, freeCapacity: 6000 }, { fillType: 'WHEAT', amount: 30000, freeCapacity: 0 }],
  neighbors: [
    { id: 4, name: 'Gerd Albers', role: 'DAIRY', trustLevel: 'GOOD', needs: ['STRAW', 'SILAGE', 'DRYGRASS_WINDROW'], farmlands: [7, 9],
      stock: [{ fillType: 'STRAW', amount: 8000, unitPrice: 126 }, { fillType: 'WHEAT', amount: 5000, unitPrice: 210 }, { fillType: 'SILAGE', amount: 900, unitPrice: null }] },
    { id: 5, name: 'Jana Wulf', role: 'ARABLE', trustLevel: 'NEUTRAL', needs: ['SEEDS'], farmlands: [], stock: [] },
  ],
  cases: [],
  ...over,
});
const goods = (over: Partial<CaseView> = {}): CaseView => ({
  id: 31, kind: 'GOODS_OFFER', status: 'AWAITING_PLAYER', character: { id: 4, name: 'Gerd Albers', role: 'NEIGHBOR_FARMER' } as CaseView['character'],
  farmlandId: null, hectares: null, damageAmount: null, payoutAmount: null, costAmount: 126, offerAmount: 756, roundsUsed: 0,
  measureAgreed: false, reference: 'STRAW', quantity: 6000, gameTime: 10 * DAY, deadlineGameTime: 15 * DAY, resolution: null,
  measureCost: null, ...over,
});

describe('Trade', () => {
  function setup(t: TradeView = view(), cases: CaseView[] = [], neighbor?: string) {
    TestBed.configureTestingModule({
      imports: [Trade],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Trade);
    if (neighbor) fixture.componentRef.setInput('neighbor', neighbor);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/trade').flush(t);
    http.match('/api/contracts').forEach((r) => r.flush([]));
    http.match('/api/cases').forEach((r) => r.flush(cases));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, http, el };
  }

  it('offers only goods with a price and room in an own silo, up to stock and free capacity', () => {
    const t = view();
    expect(requestables(t.neighbors[0], t)).toEqual([{ fillType: 'STRAW', max: 6000 }]);
    expect(requestables(t.neighbors[1], t)).toEqual([]);
  });

  it('shows silos, neighbours with role, stock, needs and fields', () => {
    const { el } = setup();
    expect(el.querySelectorAll('[data-testid="silo"]')[0].textContent).toContain('Stroh');
    const cards = el.querySelectorAll('[data-testid="neighbor"]');
    expect(cards.length).toBe(2);
    expect(cards[0].querySelector('[data-testid="role"]')?.textContent).toContain('Milchviehbetrieb');
    expect(cards[0].querySelector('[data-testid="stock"]')?.textContent).toContain('kein Preis');
    expect(cards[0].querySelector('[data-testid="needs"]')?.textContent).toContain('Heu');
    expect(cards[0].querySelector('[data-testid="farmlands"]')?.textContent).toContain('7, 9');
    expect(cards[0].querySelector('[data-testid="ask-work"]')).not.toBeNull();
    expect(cards[1].textContent).toContain('Kein Vorrat');
    expect(cards[1].querySelector('[data-testid="request-form"]')).toBeNull();
    expect(cards[1].querySelector('[data-testid="ask-work"]')).toBeNull();
    expect(el.querySelector('[data-testid="no-silos"]')).toBeNull();
  });

  it('requests goods from a neighbour and shows the new offer', () => {
    const { el, fixture, http } = setup();
    const card = el.querySelectorAll('[data-testid="neighbor"]')[0];
    const amount = card.querySelector('[data-testid="request-amount"]') as HTMLInputElement;
    expect(amount.value).toBe('6000');
    amount.value = '2500';
    amount.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (card.querySelector('[data-testid="request-form"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/trade/neighbors/4/request');
    expect(req.request.body).toEqual({ fillType: 'STRAW', amount: 2500 });
    req.flush(goods({ quantity: 2500 }));
    http.expectOne('/api/trade').flush(view());
    http.match('/api/contracts').forEach((r) => r.flush([]));
    http.match('/api/cases').forEach((r) => r.flush([goods({ quantity: 2500, offerAmount: 315 })]));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="trade-info"]')?.textContent).toContain('Gerd Albers');
    expect(el.querySelector('[data-testid="goods-case"]')?.textContent).toContain('2500 l Stroh');
  });

  it('shows the reason a request is refused', () => {
    const { el, fixture, http } = setup();
    const card = el.querySelectorAll('[data-testid="neighbor"]')[0];
    (card.querySelector('[data-testid="request-form"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http.expectOne('/api/trade/neighbors/4/request').flush({ code: 'TRADE_FUNDS', message: 'Dafür reicht dein Kontostand nicht (756 €).' },
      { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(card.querySelector('[data-testid="trade-error"]')?.textContent).toContain('Kontostand');
  });

  it('asks a neighbour for work', () => {
    const { el, http } = setup();
    (el.querySelector('[data-testid="ask-work"] button') as HTMLButtonElement).click();
    http.expectOne('/api/trade/neighbors/4/work').flush(goods({ kind: 'NEIGHBOR_MISSION', reference: 'PLOW', hectares: 3.2, offerAmount: 250 }));
    http.expectOne('/api/trade').flush(view());
  });

  it('explains missing mod data and the reached contract limit', () => {
    const { el } = setup(view({ silosTracked: false, fieldsTracked: false, missionLimitReached: true, silos: [] }));
    expect(el.querySelector('[data-testid="no-silos"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="no-fields"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="limit-reached"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="request-form"]')).toBeNull();
    expect(el.querySelector('[data-testid="ask-work"]')).toBeNull();
  });

  it('highlights the neighbour from the contacts link', () => {
    const { el } = setup(view(), [], '5');
    const cards = el.querySelectorAll('[data-testid="neighbor"] > section');
    expect(cards[1].classList).toContain('border-accent');
    expect(cards[0].classList).not.toContain('border-accent');
  });

  it('lets the player buy an offer and accept a neighbour contract', () => {
    const { el, http, fixture } = setup(view(), [goods(), goods({ id: 32, kind: 'NEIGHBOR_MISSION', reference: 'STONE_PICK', hectares: 3.2, offerAmount: 250, quantity: null })]);
    expect(el.querySelector('[data-testid="neighbor-mission"]')?.textContent).toContain('Steine sammeln');
    const accept = el.querySelectorAll('[data-testid="case-accept"] button')[0] as HTMLButtonElement;
    expect(accept.textContent).toContain('Kaufen');
    accept.click();
    http.expectOne('/api/cases/31/accept').flush(goods({ status: 'IN_PROGRESS' }));
    fixture.detectChanges();
  });
});
