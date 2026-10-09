import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BulkOrderMonthView, BulkOrdersView, CaseView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { CaseCard } from '../contracts/case-card';
import { BulkOrdersCard } from './bulk-orders-card';

const providers = [provideHttpClient(), provideHttpClientTesting()];

const request = (over: Partial<CaseView> = {}): CaseView => ({
  id: 7, kind: 'BULK_ORDER', status: 'AWAITING_PLAYER',
  character: { id: 3, name: 'Jonas Albers', role: 'BULK_BUYER' } as CaseView['character'],
  farmlandId: null, hectares: null, damageAmount: null, payoutAmount: null, costAmount: 288, offerAmount: 144000,
  roundsUsed: 0, measureAgreed: false, reference: 'CANOLA', quantity: 500000, gameTime: DAY, deadlineGameTime: 6 * DAY,
  resolution: null, measureCost: null, title: 'Ölmühle Nord', ...over,
});

const month = (lead: number, over: Partial<BulkOrderMonthView> = {}): BulkOrderMonthView => ({
  leadMonths: lead, monthIndex: 10 + lead, period: lead, startGameTime: (10 + lead) * DAY, deadlineGameTime: (11 + lead) * DAY,
  fixedPrice: 450 + lead, expectedIncome: 500 * (450 + lead), available: true, reason: null, ...over,
});

function card(c: CaseView) {
  TestBed.configureTestingModule({ imports: [CaseCard], providers });
  const fixture = TestBed.createComponent(CaseCard);
  fixture.componentRef.setInput('c', c);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, http: TestBed.inject(HttpTestingController) };
}

const click = (el: HTMLElement, id: string) => (el.querySelector(`[data-testid="${id}"] button`) as HTMLButtonElement).click();

describe('Bulk order request (R32-G)', () => {
  it('names buyer, sell point, amount and the instant price', () => {
    const { el } = card(request());
    const text = el.querySelector('[data-testid="bulk-order-request"]')!.textContent!;
    expect(text).toContain('Jonas Albers');
    expect(text).toContain('Ölmühle Nord');
    expect(text).toContain('500.000 l Raps');
  });

  it('delivers at once and declines through the case actions', () => {
    const { el, http } = card(request());
    click(el, 'bulk-deliver');
    http.expectOne('/api/cases/7/accept').flush(request({ status: 'IN_PROGRESS' }));
    click(el, 'bulk-decline');
    http.expectOne('/api/cases/7/decline').flush(request({ status: 'DECLINED' }));
  });

  it('agrees a delivery month the backend names; busy months cannot be chosen', () => {
    const { el, fixture, http } = card(request());
    click(el, 'bulk-term');
    http.expectOne('/api/trade/bulk-orders/7/months').flush([month(1, { available: false, reason: 'FIXED_PRICE_BUSY' }),
      month(2), month(3)]);
    fixture.detectChanges();
    const select = el.querySelector('[data-testid="bulk-month"]') as HTMLSelectElement;
    const options = Array.from(select.options);
    expect(options[1].disabled).toBe(true);
    expect(options[1].textContent).toContain('schon ein Festpreis');
    select.value = '2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="bulk-quote"]')?.textContent).toContain('452');
    click(el, 'bulk-agree');
    const req = http.expectOne('/api/trade/bulk-orders/7/term');
    expect(req.request.body).toEqual({ leadMonths: 2 });
  });

  it('shows an agreed delivery month instead of the buttons', () => {
    const { el } = card(request({ status: 'SETTLED', resolution: 'TERM_AGREED' }));
    expect(el.querySelector('[data-testid="bulk-deliver"]')).toBeNull();
    expect(el.querySelector('[data-testid="bulk-agreed"]')).not.toBeNull();
  });
});

describe('BulkOrdersCard (R32-G3/G4)', () => {
  const view = (over: Partial<BulkOrdersView> = {}): BulkOrdersView => ({
    silosTracked: true, minLeadMonths: 1, maxLeadMonths: 12, maxOpen: 3, open: 1, instantMarkupPercent: 25,
    penaltySharePercent: 25, requests: [], orders: [
      { id: 1, caseId: 7, fillType: 'CANOLA', sellPoint: 'OilMillNorth', sellPointName: 'Ölmühle Nord', buyerName: 'Jonas Albers',
        quantity: 500000, fixedPrice: 452, expectedIncome: 226000, leadMonths: 2, deliveryStartGameTime: 12 * DAY,
        deadlineGameTime: 13 * DAY, deliveryPeriod: 2, status: 'OPEN', deliveredQuantity: null, penalty: null },
      { id: 2, caseId: 5, fillType: 'WHEAT', sellPoint: 'MillSouth', sellPointName: 'Mühle Süd', buyerName: null,
        quantity: 100000, fixedPrice: 250, expectedIncome: 25000, leadMonths: 1, deliveryStartGameTime: 5 * DAY,
        deadlineGameTime: 6 * DAY, deliveryPeriod: 1, status: 'SHORTFALL', deliveredQuantity: 80000, penalty: 1250 }],
    ...over,
  });

  it('lists the orders with delivery month, result and penalty', () => {
    TestBed.configureTestingModule({ imports: [BulkOrdersCard], providers });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(BulkOrdersCard);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/trade/bulk-orders').flush(view({ silosTracked: false }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const orders = el.querySelectorAll('[data-testid="bulk-order"]');
    expect(orders.length).toBe(2);
    expect(orders[0].textContent).toContain('Ölmühle Nord');
    expect(orders[0].textContent).toContain('offen');
    expect(el.querySelector('[data-testid="bulk-result"]')?.textContent).toContain('80.000 von 100.000 l');
    expect(el.querySelector('[data-testid="bulk-result"]')?.textContent).toContain('Vertragsstrafe');
    expect(el.querySelector('[data-testid="bulk-no-silos"]')).not.toBeNull();
  });
});
