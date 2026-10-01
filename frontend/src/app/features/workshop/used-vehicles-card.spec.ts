import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { NegotiationView, VehicleDealView, VehiclesView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { taskAppId } from '../../layout/task-apps';
import { UsedVehiclesCard } from './used-vehicles-card';

const negotiation = (over: Partial<NegotiationView> = {}): NegotiationView => ({
  id: 5, assetType: 'VEHICLE', assetId: '1', kind: 'DIRECT', direction: 'PLAYER_BUYS', initiatedBy: 'CHARACTER',
  status: 'OPEN', counterpart: { id: 3, name: 'Meister Lüdtke', role: 'WORKSHOP', status: 'ACTIVE' }, announcer: null,
  basePrice: 90_000, askingPrice: null, roundsUsed: 0, maxRounds: 3, lastCounterOffer: 90_000, finalPrice: null,
  closesAtGameTime: 17 * DAY, winner: null, offers: [], ...over,
});

const deal = (over: Partial<VehicleDealView> = {}): VehicleDealView => ({
  id: 1, direction: 'BUY', status: 'OPEN', sellerKind: 'WORKSHOP',
  character: { id: 3, name: 'Meister Lüdtke', role: 'WORKSHOP', status: 'ACTIVE' }, vehicleName: 'Fendt 700 Vario',
  categoryName: 'TRACTORSL', listPrice: 245_000, ageMonths: 36, operatingHours: 2400, damage: 0.2, wear: 0.3,
  gamePrice: 81_818, basePrice: 90_000, askingPrice: null, finalPrice: null, vehicleId: null, attempts: 0,
  nextAttemptGameTime: null, failureReason: null, createdGameTime: 10 * DAY, closedGameTime: null,
  negotiations: [negotiation()], ...over,
});

const view = (deals: VehicleDealView[]): VehiclesView => ({
  enabled: true, maxRounds: 3, saleCapPercent: 110, spawnMaxAttempts: 5,
  vehicles: [{ uniqueId: 'veh_00042', name: 'Deutz-Fahr 6165', value: 285_000, condition: 82, saleDealId: null },
    { uniqueId: 'veh_00043', name: null, value: 12_000, condition: null, saleDealId: 9 }],
  deals,
});

describe('UsedVehiclesCard', () => {
  function setup(deals: VehicleDealView[]) {
    TestBed.configureTestingModule({
      imports: [UsedVehiclesCard],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(UsedVehiclesCard);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/vehicles').flush(view(deals));
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  function type(el: HTMLElement, testId: string, value: string) {
    const input = el.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('shows an offer with its used values and sends the player offer from the form', () => {
    const { fixture, http, el } = setup([deal()]);
    const offer = el.querySelector('[data-testid="vehicle-offer"]')!;
    expect(offer.textContent).toContain('Fendt 700 Vario');
    expect(offer.textContent).toContain('3 Jahre');
    expect(offer.textContent).toContain('2400 Betriebsstunden');
    expect(offer.textContent).toContain('20 % Schaden');
    expect(offer.textContent).toContain('Werkstatt');
    expect(el.querySelector('[data-testid="vehicle-price"]')?.textContent).toContain('90.000');
    type(el, 'vehicle-amount', '82000');
    fixture.detectChanges();
    el.querySelector('[data-testid="vehicle-offer"] form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/negotiations/5/offer');
    expect(req.request.body).toEqual({ amount: 82000 });
  });

  it('shows a delivery waiting for space and the history', () => {
    const { el } = setup([deal({ status: 'AGREED', finalPrice: 85_000, attempts: 2, nextAttemptGameTime: 12 * DAY }),
      deal({ id: 2, status: 'FAILED', failureReason: 'VEHICLE_ATTACHED', direction: 'SELL', closedGameTime: 11 * DAY })]);
    expect(el.querySelector('[data-testid="vehicle-delivery"]')?.textContent).toContain('Versuch 2 von 5');
    const history = el.querySelector('[data-testid="vehicle-history"]')!.textContent ?? '';
    expect(history).toContain('Geplatzt');
    expect(history).toContain('erst abkoppeln');
  });

  it('offers an own machine for sale with the game value as default asking price', () => {
    const { fixture, http, el } = setup([]);
    const own = el.querySelectorAll('[data-testid="own-vehicle"]');
    expect(own[0].textContent).toContain('Deutz-Fahr 6165');
    expect(own[1].textContent).toContain('veh_00043');
    expect(own[1].textContent).toContain('Zum Verkauf angeboten');
    expect((el.querySelector('[data-testid="vehicle-asking"]') as HTMLInputElement).value).toBe('285000');
    type(el, 'vehicle-asking', '300000');
    fixture.detectChanges();
    own[0].querySelector('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/vehicles/veh_00042/sale');
    expect(req.request.body).toEqual({ askingPrice: 300000 });
  });

  it('accepts the bid of a neighbour or demands more', () => {
    const buyer = negotiation({ id: 7, kind: 'SALE_OFFER', direction: 'PLAYER_SELLS', lastCounterOffer: 300_000,
      counterpart: { id: 4, name: 'Bauer Jansen', role: 'NEIGHBOR_FARMER', status: 'ACTIVE' } });
    const { fixture, http, el } = setup([deal({ id: 9, direction: 'SELL', sellerKind: null, askingPrice: 320_000,
      gamePrice: 285_000, negotiations: [buyer] })]);
    expect(el.querySelector('[data-testid="vehicle-buyer"]')?.textContent).toContain('Bauer Jansen');
    (el.querySelector('[data-testid="vehicle-accept-bid"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/negotiations/7/offer').request.body).toEqual({ amount: 300000 });
    type(el, 'vehicle-demand', '310000');
    fixture.detectChanges();
    el.querySelector('[data-testid="vehicle-buyer"]')!.dispatchEvent(new Event('submit'));
    expect(http.expectOne('/api/negotiations/7/offer').request.body).toEqual({ amount: 310000 });
  });

  it('opens machine negotiations of the task list in the workshop', () => {
    const t = { type: 'NEGOTIATION', negotiation: negotiation() } as unknown as Parameters<typeof taskAppId>[0];
    expect(taskAppId(t)).toBe('workshop');
    const field = { type: 'NEGOTIATION', negotiation: negotiation({ assetType: 'FARMLAND' }) } as unknown as Parameters<typeof taskAppId>[0];
    expect(taskAppId(field)).toBe('fields');
  });
});
