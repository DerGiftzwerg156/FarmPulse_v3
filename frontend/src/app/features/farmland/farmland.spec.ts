import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { FarmlandView, FieldMapView, MessageView, NegotiationView } from '../../core/api/models';
import { character, message } from '../../../testing/fixtures';
import { Farmland, acceptableAmount, highestBid } from './farmland';

const gerd = character({ id: 4, name: 'Gerd Albers', role: 'NEIGHBOR_FARMER' });
const fields: FarmlandView[] = [
  { farmlandId: 1, hectares: 3.2, referencePrice: 96000, ownerType: 'PLAYER', owner: null, inNegotiation: false, leaseOutGuideRate: 125 },
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
  function setup(negotiations: NegotiationView[] = [], mails: MessageView[] = [], negotiationParam?: string,
                 fieldMap?: FieldMapView) {
    TestBed.configureTestingModule({
      imports: [Farmland],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Farmland);
    if (negotiationParam) fixture.componentRef.setInput('negotiation', negotiationParam);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/field-map').flush(fieldMap ?? { mapSize: null, fields: [] });
    http.expectOne('/api/farmlands').flush(fields);
    http.expectOne('/api/negotiations').flush(negotiations);
    http.expectOne('/api/mails').flush(mails);
    http.expectOne('/api/lease-out').flush({ termYearsMin: 1, termYearsMax: 3, contracts: [] });
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

  it('marks fields that cannot be traded in the game and offers no actions (TODO T-11)', () => {
    fields[1].tradeable = false;
    try {
      const { el, tile } = setup();
      tile(1);
      expect(el.querySelector('[data-testid="not-tradeable"]')).not.toBeNull();
      expect(el.querySelector('[data-testid="start-direct"]')).toBeNull();
      expect(el.querySelector('[data-testid="field-detail"]')?.textContent).toContain('nicht handelbar');
    } finally {
      delete fields[1].tradeable;
    }
  });

  // Roadmap V2 R2-C
  it('shows crop and growth phase of an own field', () => {
    Object.assign(fields[0], { fruitType: 'WHEAT', phase: 'HARVESTABLE' });
    try {
      const { el, tile } = setup();
      tile(0);
      expect(el.querySelector('[data-testid="field-crop"]')?.textContent).toContain('Weizen');
      expect(el.querySelector('[data-testid="field-crop"]')?.textContent).toContain('erntereif');
      tile(1);
      expect(el.querySelector('[data-testid="field-crop"]')).toBeNull();
    } finally {
      delete fields[0].fruitType;
      delete fields[0].phase;
    }
  });

  it('asks the owner for a lease and marks leased fields (TODO T-22)', () => {
    const { el, tile, btn, http, fixture } = setup();
    tile(1);
    btn('request-lease').click();
    http.expectOne('/api/farmlands/2/lease-request').flush({ id: 9, kind: 'LEASE', status: 'OFFERED' });
    http.expectOne('/api/farmlands').flush(fields.map((f) => (f.farmlandId === 2 ? { ...f, leased: true } : f)));
    http.expectOne('/api/negotiations').flush([]);
    http.expectOne('/api/mails').flush([]);
    http.expectOne('/api/lease-out').flush({ termYearsMin: 1, termYearsMax: 3, contracts: [] });
    fixture.detectChanges();
    expect(el.textContent).toContain('Gerd Albers bietet dir eine Pacht an');
    expect(el.querySelector('[data-testid="leased"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="start-direct"]')).toBeNull();
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

  // Roadmap V2 R2-E3
  it('marks an own field as the family field and removes the mark', () => {
    const { el, btn, http, fixture, tile } = setup();
    tile(0);
    btn('family-field-mark').click();
    const req = http.expectOne('/api/farmlands/1/family-field');
    expect(req.request.method).toBe('PUT');
    req.flush(null);
    http.expectOne('/api/farmlands').flush([{ ...fields[0], familyField: true }, fields[1], fields[2]]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="family-field"]')?.textContent).toContain('Familienfeld');
    btn('family-field-clear').click();
    const clear = http.expectOne('/api/family-field');
    expect(clear.request.method).toBe('DELETE');
    clear.flush(null);
    http.expectOne('/api/farmlands').flush(fields);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="family-field"]')).toBeNull();
  });

  // Roadmap V3 R3-L1
  it('offers an own field for lease with term and desired rent per ha and month', () => {
    const { el, tile, btn, http, fixture, setAmount } = setup();
    tile(0);
    const term = el.querySelector('[data-testid="lease-out-term"]') as HTMLSelectElement;
    expect([...term.options].map((o) => o.value)).toEqual(['1', '2', '3']);
    expect((el.querySelector('[data-testid="lease-out-rate"]') as HTMLInputElement).value).toBe('125');
    const guide = el.querySelector('[data-testid="lease-out-guide"]')?.textContent?.replace(/\s/g, ' ');
    expect(guide).toContain('125 € je ha und Monat');
    expect(guide).toContain('400 € im Monat');
    term.value = '2';
    term.dispatchEvent(new Event('change'));
    setAmount('lease-out-rate', '130');
    btn('lease-out-submit').click();
    const req = http.expectOne('/api/farmlands/1/lease-out');
    expect(req.request.body).toEqual({ termYears: 2, desiredRate: 130 });
    req.flush([neg({ id: 11, assetId: '1', kind: 'LEASE_OFFER', direction: 'PLAYER_SELLS', basePrice: 125, askingPrice: 130,
      leaseTermMonths: 24, offers: [
        { round: 0, offeredBy: 'CHARACTER', characterName: 'Gerd Albers', amount: 118, result: 'BID', counterAmount: null, gameTime: 0 },
      ] })]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="farmland-info"]')?.textContent).toContain('1 Nachbar(n)');
    expect(el.querySelector('[data-testid="negotiation-detail"]')?.textContent).toContain('Verpachtung');
    expect(el.querySelector('[data-testid="lease-out-terms"]')?.textContent).toContain('Laufzeit 2 Jahr(e)');
    expect(el.querySelector('[data-testid="accept-counter"]')?.textContent?.replace(/\s/g, ' ')).toContain('118 € annehmen');
  });

  it('shows a leased-out field without sale or lease form', () => {
    fields[0].leasedOut = true;
    try {
      const { el, tile } = setup();
      tile(0);
      expect(el.querySelector('[data-testid="leased-out"]')).not.toBeNull();
      expect(el.querySelector('[data-testid="leased-out-hint"]')).not.toBeNull();
      expect(el.querySelector('[data-testid="sell-form"]')).toBeNull();
      expect(el.querySelector('[data-testid="lease-out-form"]')).toBeNull();
    } finally {
      fields[0].leasedOut = false;
    }
  });

  it('R31-K1: shows the map by default once outlines exist and opens the field card on a click', () => {
    const square = [{ x: 0, z: 0 }, { x: 100, z: 0 }, { x: 100, z: 100 }];
    const map: FieldMapView = { mapSize: 2048, fields: [
      { farmlandId: 1, name: '1', points: square, kind: 'OWN', ownerName: null, leased: false, leasedOut: false,
        fruitType: 'WHEAT', phase: 'HARVESTABLE', orders: [], auction: false, hints: ['HARVESTABLE'] },
    ] };
    const { el, fixture } = setup([], [], undefined, map);
    expect(el.querySelector('[data-testid="field-map-svg"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="field-tile"]')).toBeNull();
    (el.querySelector('[data-testid="map-field"]') as SVGGElement).dispatchEvent(new Event('click'));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="field-detail"]')).not.toBeNull();
    (el.querySelector('[data-testid="view-table"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="field-tile"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="field-map-svg"]')).toBeNull();
  });

  it('R31-K1: without outlines only the tiles and a hint', () => {
    const { el } = setup();
    expect(el.querySelector('[data-testid="map-switch"]')).toBeNull();
    expect(el.querySelector('[data-testid="map-missing"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="field-tile"]')).not.toBeNull();
  });
});
