import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CaseView, PriceView } from '../../core/api/models';
import { DAY } from '../../../testing/fixtures';
import { CaseCard } from '../contracts/case-card';
import { ForwardContractCard } from './forward-contract-card';
import { PriceAlarmCard } from './price-alarm-card';

const providers = [provideRouter([]), provideHttpClient(), provideHttpClientTesting()];
const prices: PriceView[] = [
  { sellPoint: 'MillNorth', sellPointName: 'Mühle Nord', fillType: 'WHEAT', currentPrice: 215 },
  { sellPoint: 'MillSouth', sellPointName: 'Mühle Süd', fillType: 'WHEAT', currentPrice: 230 },
];

describe('PriceAlarmCard (R3-M1)', () => {
  it('creates an alarm for any sell point and re-activates a fired one', () => {
    TestBed.configureTestingModule({ imports: [PriceAlarmCard], providers });
    const fixture = TestBed.createComponent(PriceAlarmCard);
    fixture.componentRef.setInput('prices', prices);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/price-alarms').flush({ maxActive: 10, alarms: [
      { id: 3, fillType: 'WHEAT', sellPoint: null, threshold: 220, direction: 'ABOVE', status: 'FIRED', createdGameTime: DAY,
        firedGameTime: 2 * DAY, firedPrice: 230, firedSellPoint: 'MillSouth' },
    ] });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="alarm"]')?.textContent).toContain('ausgelöst');
    const threshold = el.querySelector('[data-testid="alarm-threshold"]') as HTMLInputElement;
    threshold.value = '240';
    threshold.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="alarm-form"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/price-alarms');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ fillType: 'WHEAT', sellPoint: null, threshold: 240, direction: 'ABOVE' });
    req.flush({});
    http.expectOne('/api/price-alarms').flush({ maxActive: 10, alarms: [] });

    fixture.detectChanges();
  });
});

describe('ForwardContractCard (R3-M2)', () => {
  it('asks for the fixed price and concludes the contract', () => {
    TestBed.configureTestingModule({ imports: [ForwardContractCard], providers });
    const fixture = TestBed.createComponent(ForwardContractCard);
    fixture.componentRef.setInput('prices', prices);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/forward-contracts').flush({ minLeadMonths: 1, maxLeadMonths: 12, minQuantity: 1000, maxQuantity: 200000,
      quantityStep: 1000, maxOpen: 5, factorPerMonthPercent: -2, penaltySharePercent: 25, contracts: [
        { id: 1, fillType: 'WHEAT', sellPoint: 'MillSouth', quantity: 10000, fixedPrice: 225, basePrice: 230, leadMonths: 1,
          deliveryStartGameTime: 11 * DAY, deadlineGameTime: 12 * DAY, status: 'SHORTFALL', deliveredQuantity: 6000, penalty: 225, createdGameTime: DAY },
      ] });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="forward-contract"]')?.textContent).toContain('Vertragsstrafe');
    (el.querySelector('[data-testid="forward-form"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const q = http.expectOne('/api/forward-contracts/quote');
    expect(q.request.body).toEqual({ fillType: 'WHEAT', sellPoint: 'MillNorth', quantity: 10000, leadMonths: 1 });
    q.flush({ fillType: 'WHEAT', sellPoint: 'MillNorth', quantity: 10000, leadMonths: 1, basePrice: 215, fixedPrice: 211,
      deliveryStartGameTime: 11 * DAY, deadlineGameTime: 12 * DAY, deliveryPeriod: 12, expectedIncome: 2110 });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="forward-quote"]')?.textContent).toContain('Februar');
    (el.querySelector('[data-testid="forward-conclude"] button') as HTMLButtonElement).click();
    const c = http.expectOne((r) => r.url === '/api/forward-contracts' && r.method === 'POST');
    expect(c.request.body).toEqual({ fillType: 'WHEAT', sellPoint: 'MillNorth', quantity: 10000, leadMonths: 1 });
    c.flush({});
    http.expectOne('/api/forward-contracts').flush({ minLeadMonths: 1, maxLeadMonths: 12, minQuantity: 1000, maxQuantity: 200000,
      quantityStep: 1000, maxOpen: 5, factorPerMonthPercent: -2, penaltySharePercent: 25, contracts: [] });
  });
});

describe('Farm-shop order (R3-M3)', () => {
  it('lets the player deliver an order', () => {
    TestBed.configureTestingModule({ imports: [CaseCard], providers });
    const fixture = TestBed.createComponent(CaseCard);
    const c: CaseView = { id: 9, kind: 'FARM_SHOP_ORDER', status: 'AWAITING_PLAYER', character: { id: 2, name: 'Grete Lammers', role: 'VILLAGER' } as CaseView['character'],
      farmlandId: null, hectares: null, damageAmount: null, payoutAmount: null, costAmount: 299, offerAmount: 150,
      roundsUsed: 0, measureAgreed: false, reference: 'WHEAT', quantity: 500, gameTime: DAY, deadlineGameTime: 4 * DAY,
      resolution: null, measureCost: null };
    fixture.componentRef.setInput('c', c);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="farm-shop-order"]')?.textContent).toContain('500 l Weizen');
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    TestBed.inject(HttpTestingController).expectOne('/api/cases/9/accept').flush({ ...c, status: 'IN_PROGRESS' });
  });
});
