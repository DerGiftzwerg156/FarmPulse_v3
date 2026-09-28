import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CaseView, ContractView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { Contracts } from './contracts';

const contract = (over: Partial<ContractView> = {}): ContractView => ({
  id: 1, kind: 'INSURANCE', status: 'OFFERED', character: null, level: 'BASIC', farmlandId: null, monthlyAmount: 50,
  coveragePercent: 60, deductible: 2000, termMonths: null, startedAtGameTime: null, endsAtGameTime: null,
  nextDueGameTime: null, offerExpiresAtGameTime: 12 * DAY, missedPayments: 0, paymentOverdue: false, endReason: null, ...over,
});
const damage = (over: Partial<CaseView> = {}): CaseView => ({
  id: 7, kind: 'HAIL_DAMAGE', status: 'AWAITING_PLAYER', character: null, farmlandId: 12, hectares: 4.5, damageAmount: 3600,
  payoutAmount: null, costAmount: null, offerAmount: null, roundsUsed: 0, measureAgreed: false, reference: null,
  gameTime: 10 * DAY, deadlineGameTime: 15 * DAY, resolution: null, measureCost: null, ...over,
});

describe('Contracts', () => {
  function setup(contracts: ContractView[], cases: CaseView[]) {
    TestBed.configureTestingModule({
      imports: [Contracts],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Contracts);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/contracts').flush(contracts);
    http.expectOne('/api/cases').flush(cases);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the insurance offer with its conditions and accepts it', () => {
    const { fixture, http, el } = setup([contract()], []);
    http.expectOne('/api/insurance/quotes').flush([{ level: 'COMFORT', monthlyPremium: 131, coveragePercent: 90, deductible: 500 }]);
    fixture.detectChanges();
    const offer = el.querySelector('[data-testid="insurance-offer"]')!;
    expect(offer.textContent).toContain('Basis');
    expect(offer.textContent).toContain('60 % Erstattung');
    expect(el.querySelectorAll('[data-testid="insurance-quote"]').length).toBe(1);
    (el.querySelector('[data-testid="offer-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/contracts/1/accept').flush(contract({ status: 'ACTIVE' }));
    http.expectOne('/api/contracts').flush([contract({ status: 'ACTIVE', nextDueGameTime: 11 * DAY })]);
    http.expectOne('/api/cases').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="insurance-active"]')?.textContent).toContain('Aktiv');
  });

  it('negotiates a wildlife damage: counter demand from the form field', () => {
    const { fixture, http, el } = setup([], [damage({ kind: 'WILDLIFE_DAMAGE', offerAmount: 900, measureCost: 400 })]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="wildlife-offer"]')?.textContent).toContain('900');
    expect(el.querySelector('[data-testid="case-measure"]')?.textContent).toContain('400');
    const input = el.querySelector('[data-testid="demand-input"]') as HTMLInputElement;
    input.value = '1500';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="case-counter"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/cases/7/counter');
    expect(req.request.body).toEqual({ amount: 1500 });
  });

  it('reports a damage by phone', () => {
    const { fixture, http, el } = setup([contract({ status: 'ACTIVE' })], [damage(), damage({ id: 8, status: 'SETTLED', resolution: 'PAID', payoutAmount: 160 })]);
    expect(el.querySelector('[data-testid="case"]')?.textContent).toContain('Hagelschaden');
    expect(el.querySelector('[data-testid="closed-case"]')?.textContent).toContain('Ausgezahlt');
    (el.querySelector('[data-testid="report-call"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/cases/7/report');
    expect(req.request.body).toEqual({ channel: 'CALL' });
    req.flush(damage({ status: 'SETTLED' }));
    http.expectOne('/api/contracts').flush([]);
    http.expectOne('/api/cases').flush([]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="case"]')).toBeNull();
  });
  it('accepts a trader offer and lists vet invoices in the history', () => {
    const offer = damage({ id: 9, kind: 'LIVESTOCK_OFFER', farmlandId: null, damageAmount: null, reference: 'COW', quantity: 3,
      direction: 'SELL', offerAmount: 400 });
    const vet = damage({ id: 10, kind: 'VET_VISIT', status: 'SETTLED', farmlandId: null, damageAmount: null, reference: 'COW',
      quantity: 24, costAmount: 176, resolution: 'INVOICED' });
    const { fixture, http, el } = setup([], [offer, vet]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="livestock-offer"]')?.textContent).toContain('Verkaufen: 3 Rinder');
    expect(el.querySelector('[data-testid="closed-case"]')?.textContent).toContain('24 Rinder');
    expect(el.querySelector('[data-testid="closed-case"]')?.textContent).toContain('Abgerechnet');
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/9/accept').flush({ ...offer, status: 'IN_PROGRESS' });
    http.expectOne('/api/contracts').flush([]);
    http.expectOne('/api/cases').flush([{ ...offer, status: 'IN_PROGRESS', baselineCount: 24 }]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="case"]')?.textContent).toContain('Läuft');
    expect(el.querySelector('[data-testid="case-accept"]')).toBeNull();
  });

  // Roadmap V2 R2-D2
  it('pays the compensation claimed for a field bought in the game menu', () => {
    const claim = damage({ id: 11, kind: 'COMPENSATION_CLAIM', farmlandId: 13, damageAmount: null, offerAmount: 7200,
      character: { id: 4, name: 'Bauer Jansen', role: 'NEIGHBOR_FARMER', status: 'ACTIVE' } });
    const { fixture, http, el } = setup([], [claim]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="case"]')?.textContent).toContain('Ausgleichsforderung');
    expect(el.querySelector('[data-testid="compensation-claim"]')?.textContent).toContain('Bauer Jansen verlangt 7.200');
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/11/accept').flush({ ...claim, status: 'SETTLED', resolution: 'PAID' });
    http.expectOne('/api/contracts').flush([]);
    http.expectOne('/api/cases').flush([{ ...claim, status: 'SETTLED', resolution: 'PAID' }]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="compensation-claim"]')).toBeNull();
  });

  it('shows a lease with renewal and purchase offer', () => {
    const lease = contract({ id: 5, kind: 'LEASE', status: 'ACTIVE', level: null, farmlandId: 13, monthlyAmount: 300,
      coveragePercent: null, deductible: null, termMonths: 12, endsAtGameTime: 40 * DAY, offerExpiresAtGameTime: null,
      renewalAmount: 320, purchasePrice: 75600 });
    const { fixture, http, el } = setup([lease], []);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    const row = el.querySelector('[data-testid="contract"][data-kind="LEASE"]')!;
    expect(row.textContent).toContain('Feld 13');
    expect(el.querySelector('[data-testid="lease-renew"]')?.textContent).toContain('320');
    expect(el.querySelector('[data-testid="lease-buy"]')?.textContent).toContain('75.600');
    (el.querySelector('[data-testid="lease-buy"] button') as HTMLButtonElement).click();
    http.expectOne('/api/contracts/5/buy').flush({ ...lease, status: 'ENDED', endReason: 'PURCHASED' });
  });

  it('requests and accepts a maintenance contract, repairs appear in the history', () => {
    const repair = damage({ id: 11, kind: 'REPAIR', status: 'SETTLED', farmlandId: null, damageAmount: null, reference: 'veh_a',
      quantity: 30, resolution: 'INCLUDED' });
    const { fixture, http, el } = setup([], [repair]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="closed-case"]')?.textContent).toContain('Fahrzeug veh_a (30 %)');
    expect(el.querySelector('[data-testid="closed-case"]')?.textContent).toContain('Im Wartungsvertrag repariert');
    (el.querySelector('[data-testid="request-maintenance"] button') as HTMLButtonElement).click();
    const offer = contract({ id: 6, kind: 'MAINTENANCE', status: 'OFFERED', level: null, monthlyAmount: 600, coveragePercent: null,
      deductible: null });
    http.expectOne('/api/maintenance/offer').flush(offer);
    http.expectOne('/api/contracts').flush([offer]);
    http.expectOne('/api/cases').flush([repair]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="request-maintenance"]')).toBeNull();
    (el.querySelector('[data-testid="contract-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/contracts/6/accept').flush({ ...offer, status: 'ACTIVE' });
  });

  it('shows a referred vanilla contract with the hint to take it in the game', () => {
    const ref = damage({ id: 12, kind: 'MISSION_REFERRAL', farmlandId: 7, damageAmount: null, offerAmount: 5200, title: 'Ernte',
      reference: 'm1', deadlineGameTime: null });
    const { fixture, http, el } = setup([], [ref, { ...ref, id: 13, status: 'SETTLED', resolution: 'COMPLETED' }]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mission-referral"]')?.textContent).toContain('Ernte');
    expect(el.querySelector('[data-testid="case"]')?.textContent).toContain('Feld 7');
    expect(el.querySelector('[data-testid="closed-case"]')?.textContent).toContain('Erledigt');
  });

  // Roadmap V2 R2-E
  it('pays a tax bill with its late fees by button', () => {
    const bill = damage({ id: 20, kind: 'TAX_BILL', farmlandId: null, damageAmount: null, offerAmount: 4000, costAmount: 40,
      reference: 'ASSESSMENT', title: 'Steuerbescheid Jahr 1', quantity: 1, roundsUsed: 1,
      character: { id: 9, name: 'Frau Kramer', role: 'TAX_OFFICE', status: 'ACTIVE' } });
    const { fixture, http, el } = setup([], [bill]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="case"]')?.textContent).toContain('Steuerbescheid');
    expect(el.querySelector('[data-testid="tax-bill"]')?.textContent).toContain('4.000');
    expect(el.querySelector('[data-testid="tax-bill"]')?.textContent).toMatch(/zzgl\. 40\s€ Säumniszuschlag/);
    expect(el.querySelector('[data-testid="case-accept"]')?.textContent).toContain('4.040');
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/20/accept').flush({ ...bill, status: 'SETTLED', resolution: 'PAID', payoutAmount: 4040 });
    http.expectOne('/api/contracts').flush([]);
    http.expectOne('/api/cases').flush([{ ...bill, status: 'SETTLED', resolution: 'PAID', payoutAmount: 4040 }]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    const closed = el.querySelector('[data-testid="closed-case"]')?.textContent ?? '';
    expect(closed).toContain('Steuerbescheid Jahr 1');
    expect(closed).toContain('Bezahlt 4.040');
    expect(closed).not.toContain('Rechnung');
  });

  it('shows an authority inspection with its requirement and the fine in the history', () => {
    const inspection = damage({ id: 21, kind: 'AUTHORITY_INSPECTION', status: 'IN_PROGRESS', farmlandId: null, damageAmount: null,
      title: 'ANIMAL_WELFARE', reference: 'h1', roundsUsed: 1 });
    const fined = damage({ id: 22, kind: 'AUTHORITY_INSPECTION', status: 'SETTLED', farmlandId: 7, damageAmount: null,
      title: 'CULTIVATION_DUTY', reference: '7', costAmount: 500, resolution: 'FINED' });
    const { fixture, http, el } = setup([], [inspection, fined]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="inspection"]')?.textContent).toContain('Kontrolle: Tierwohl');
    expect(el.querySelector('[data-testid="inspection-requirement"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="case-accept"]')).toBeNull();
    const closed = el.querySelector('[data-testid="closed-case"]')?.textContent ?? '';
    expect(closed).toContain('Bewirtschaftungspflicht');
    expect(closed).toContain('Bußgeld 500');
    expect(closed).toContain('Bußgeld verhängt');
  });

  it('sponsors a club with one of the offered tiers', () => {
    const request = damage({ id: 23, kind: 'SPONSORING_REQUEST', farmlandId: null, damageAmount: null, reference: 'FIRE_BRIGADE',
      tiers: [250, 500, 1000] });
    const { fixture, http, el } = setup([], [request]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="sponsoring"]')?.textContent).toContain('Freiwillige Feuerwehr');
    const tiers = el.querySelectorAll('[data-testid="sponsor-tier"] button');
    expect(tiers.length).toBe(3);
    (tiers[1] as HTMLButtonElement).click();
    const req = http.expectOne('/api/cases/23/sponsor');
    expect(req.request.body).toEqual({ amount: 500 });
  });

  it('answers an invitation to a festival', () => {
    const invitation = damage({ id: 24, kind: 'INVITATION', farmlandId: null, damageAmount: null, reference: 'SCHUETZENFEST' });
    const { fixture, http, el } = setup([], [invitation]);
    http.expectOne('/api/insurance/quotes').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="invitation"]')?.textContent).toContain('Einladung zum Schützenfest');
    (el.querySelector('[data-testid="case-decline"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/24/decline');
  });

  it('cancels an active tax advisor contract', () => {
    const advisor = contract({ id: 30, kind: 'TAX_ADVISOR', status: 'ACTIVE', level: null, monthlyAmount: 150, coveragePercent: null,
      deductible: null, offerExpiresAtGameTime: null });
    const { fixture, http, el } = setup([contract({ status: 'ACTIVE' }), advisor], []);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="contract"][data-kind="TAX_ADVISOR"]')?.textContent).toContain('Steuerberatung');
    (el.querySelector('[data-testid="contract-cancel"] button') as HTMLButtonElement).click();
    http.expectOne('/api/contracts/30/cancel');
  });
});
