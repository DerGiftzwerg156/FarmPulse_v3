import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { FarmlandView, MessageView, NegotiationView } from '../../core/api/models';
import { character, message } from '../../../testing/fixtures';
import { Farmland, acceptableAmount, highestBid } from './farmland';

const gerd = character({ id: 4, name: 'Gerd Albers', role: 'NEIGHBOR_FARMER' });
const fields: FarmlandView[] = [
  { farmlandId: 1, hectares: 3.2, referencePrice: 96000, ownerType: 'PLAYER', owner: null, inNegotiation: false },
  { farmlandId: 2, hectares: 5.5, referencePrice: 165000, ownerType: 'CHARACTER', owner: gerd, inNegotiation: false },
  { farmlandId: 3, hectares: 2.1, referencePrice: 63000, ownerType: 'UNCLAIMED', owner: null, inNegotiation: true },
];
const neg = (over: Partial<NegotiationView> = {}): NegotiationView => ({
  id: 7, assetType: 'FARMLAND', assetId: '2', kind: 'DIRECT', direction: 'PLAYER_BUYS', initiatedBy: 'PLAYER', status: 'OPEN',
  counterpart: gerd, announcer: null, basePrice: 165000, askingPrice: null, roundsUsed: 0, maxRounds: 3, lastCounterOffer: null,
  finalPrice: null, closesAtGameTime: null, winner: null, offers: [], ...over,
});

describe('negotiation helpers', () => {
  it('knows which amount can be accepted with one click', () => {
    expect(acceptableAmount(neg())).toBeNull();
    expect(acceptableAmount(neg({ lastCounterOffer: 150000, roundsUsed: 1 }))).toBe(150000);
    expect(acceptableAmount(neg({ lastCounterOffer: 150000, roundsUsed: 3 }))).toBeNull();
    const sale = neg({ kind: 'SALE_OFFER', direction: 'PLAYER_SELLS', offers: [
      { round: 0, offeredBy: 'CHARACTER', characterName: 'Gerd Albers', amount: 90000, result: 'BID', counterAmount: null, gameTime: 0 },
    ] });
    expect(acceptableAmount(sale)).toBe(90000);
    expect(highestBid(neg({ offers: [...sale.offers, { ...sale.offers[0], amount: 95000 }] }))).toBe(95000);
  });
});

describe('Farmland', () => {
  function setup(negotiations: NegotiationView[] = [], mails: MessageView[] = [], negotiationParam?: string) {
    TestBed.configureTestingModule({
      imports: [Farmland],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Farmland);
    if (negotiationParam) fixture.componentRef.setInput('negotiation', negotiationParam);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/farmlands').flush(fields);
    http.expectOne('/api/negotiations').flush(negotiations);
    http.expectOne('/api/mails').flush(mails);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const btn = (id: string, i = 0) => el.querySelectorAll(`[data-testid="${id}"] button`)[i] as HTMLButtonElement;
    const tile = (i: number) => {
      (el.querySelectorAll('[data-testid="field-tile"]')[i] as HTMLButtonElement).click();
      fixture.detectChanges();
    };
    const setAmount = (id: string, v: string) => {
      const input = el.querySelector(`[data-testid="${id}"]`) as HTMLInputElement;
      input.value = v;
      input.dispatchEvent(new Event('input'));
    };
    return { fixture, http, el, btn, tile, setAmount };
  }

  it('renders all fields with owner state', () => {
    const { el } = setup();
    const tiles = el.querySelectorAll('[data-testid="field-tile"]');
    expect([...tiles].map((t) => t.getAttribute('data-owner'))).toEqual(['PLAYER', 'CHARACTER', 'UNCLAIMED']);
    expect(tiles[2].className).toContain('border-warn');
    expect(el.textContent).toContain('1 von 3 Feldern');
  });

  it('offers an own field for sale with a price form', () => {
    const { el, tile, btn, http, fixture, setAmount } = setup();
    tile(0);
    setAmount('asking-price', '110000');
    btn('sell-submit').click();
    const req = http.expectOne('/api/farmlands/1/sell-offer');
    expect(req.request.body).toEqual({ askingPrice: 110000 });
    req.flush([neg({ id: 9, assetId: '1', kind: 'SALE_OFFER', direction: 'PLAYER_SELLS', askingPrice: 110000, offers: [
      { round: 0, offeredBy: 'CHARACTER', characterName: 'Gerd Albers', amount: 98000, result: 'BID', counterAmount: null, gameTime: 0 },
    ] })]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="farmland-info"]')?.textContent).toContain('1 Interessent');
    expect(el.querySelector('[data-testid="negotiation-detail"]')?.textContent).toContain('Verkaufsangebot');
    expect(el.querySelector('[data-testid="accept-counter"]')?.textContent?.replace(/\s/g, ' ')).toContain('98.000 € annehmen');
  });

  it('starts a direct negotiation with the owner', () => {
    const { el, tile, btn, http, fixture } = setup();
    tile(1);
    btn('start-direct').click();
    const req = http.expectOne('/api/negotiations/direct');
    expect(req.request.body).toEqual({ characterId: 4, farmlandId: 2 });
    req.flush(neg());
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="rounds"]')?.textContent).toContain('0 / 3');
  });

  it('bids, receives a counter offer, accepts it and shows the result with the narration', () => {
    const narration = message({ id: 30, subject: 'Feld 2', body: 'Für 150.000 € wäre ich dabei.', relatedEntityType: 'NEGOTIATION', relatedEntityId: 7, category: 'NEGOTIATION' });
    const { el, btn, http, fixture, setAmount } = setup([neg()], [narration], '7');
    expect(el.querySelector('[data-testid="narration"]')?.textContent).toContain('Für 150.000 € wäre ich dabei.');
    setAmount('offer-amount', '140000');
    btn('offer-submit').click();
    const req = http.expectOne('/api/negotiations/7/offer');
    expect(req.request.body).toEqual({ amount: 140000 });
    const countered = neg({ roundsUsed: 1, lastCounterOffer: 150000, offers: [
      { round: 1, offeredBy: 'PLAYER', characterName: null, amount: 140000, result: 'COUNTER', counterAmount: 150000, gameTime: 0 },
    ] });
    req.flush({ result: 'COUNTER', counterAmount: 150000, roundsLeft: 2, negotiation: countered });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="farmland-info"]')?.textContent).toContain('Gegenangebot erhalten. Gegenangebot: 150.000 €. Noch 2 Runde(n).');
    expect(el.querySelectorAll('[data-testid="offer-history"] tbody tr').length).toBe(1);

    btn('accept-counter').click();
    const acc = http.expectOne('/api/negotiations/7/offer');
    expect(acc.request.body).toEqual({ amount: 150000 });
    acc.flush({ result: 'ACCEPTED', counterAmount: null, roundsLeft: 1, negotiation: { ...countered, status: 'ACCEPTED', finalPrice: 150000, roundsUsed: 2 } });
    http.expectOne('/api/farmlands').flush(fields);
    http.match('/api/savegame').forEach((r) => r.flush(null));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="final-price"]')?.textContent?.replace(/\s/g, ' ')).toContain('150.000 €');
    expect(el.querySelector('[data-testid="offer-form"]')).toBeNull();
  });

  it('hides the bid form when all rounds are used and shows errors from the engine', () => {
    const { el, btn, http, fixture, setAmount } = setup([neg({ id: 8, kind: 'AUCTION', announcer: gerd, counterpart: null, roundsUsed: 2 })], [], '8');
    setAmount('offer-amount', '999999999');
    btn('offer-submit').click();
    http.expectOne('/api/negotiations/8/offer').flush({ code: 'INSUFFICIENT_FUNDS', message: 'Das Gebot übersteigt den verfügbaren Kontostand.', fields: {} }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="farmland-error"]')?.textContent).toContain('Kontostand');
  });

  it('withdraws', () => {
    const { el, btn, http, fixture } = setup([neg()], [], '7');
    btn('withdraw').click();
    http.expectOne('/api/negotiations/7/withdraw').flush(neg({ status: 'WITHDRAWN' }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="negotiation-status"]')?.textContent).toContain('Zurückgezogen');
  });
});
