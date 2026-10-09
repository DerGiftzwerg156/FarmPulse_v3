import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CaseView, InvestorContractView, InvestorObligationView, InvestorsView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { caseTabOf } from '../../layout/app-tabs';
import { taskAppId, taskTabId } from '../../layout/task-apps';
import { CaseCard } from '../contracts/case-card';
import { InvestorsPanel } from './investors-panel';

const providers = [provideHttpClient(), provideHttpClientTesting()];

const obligation = (over: Partial<InvestorObligationView> = {}): InvestorObligationView => ({
  id: 11, type: 'W2', main: true, fillType: 'WHEAT', subType: null, quantity: 20000, minPerYear: null, hectares: null,
  rate: null, target: null, targetKind: null, unitPrice: 230, valuePerYear: 55200, totalValue: 165600, deliveredTotal: 0,
  consents: [], current: null, outstanding: 0, breaches: [], ...over,
});

const contract = (over: Partial<InvestorContractView> = {}): InvestorContractView => ({
  id: 5, caseId: 7, characterId: 3, investor: 'Ines Investor', kind: 'FOOD_CHAIN', kindLabel: 'Regionale Lebensmittelkette',
  packageNo: 1, amount: 1000000, capitalType: 'SILENT', years: 3, startYear: 2, endYear: 4, targetReturn: 0.08,
  targetValue: 240000, status: 'OFFERED', breaches: 0, announced: false, repaymentDue: false, extensionOf: null,
  extendedBy: null, endReason: null, considerations: [obligation()], payments: [], ...over,
});

const offerCase = (over: Partial<CaseView> = {}): CaseView => ({
  id: 7, kind: 'INVESTOR_OFFER', status: 'AWAITING_PLAYER',
  character: { id: 3, name: 'Ines Investor', role: 'INVESTOR' } as CaseView['character'],
  farmlandId: null, hectares: null, damageAmount: null, payoutAmount: null, costAmount: null, offerAmount: 1000000,
  roundsUsed: 0, measureAgreed: false, reference: 'FOOD_CHAIN', quantity: 2, gameTime: DAY, deadlineGameTime: 11 * DAY,
  resolution: null, measureCost: null, title: 'Regionale Lebensmittelkette', ...over,
} as CaseView);

const view = (over: Partial<InvestorsView> = {}): InvestorsView => ({
  enabled: true, savegameEnabled: true, maxActive: 2, running: 0, breachesToTerminate: 3, graceDays: 5,
  compensationMarkup: 1.25, offers: [], contracts: [], cases: [], stalls: [], fields: [], ...over,
});

function panel(v: InvestorsView) {
  TestBed.configureTestingModule({ imports: [InvestorsPanel], providers });
  TestBed.inject(GameStateStore).savegame.set(savegame({}));
  const fixture = TestBed.createComponent(InvestorsPanel);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/investors').flush(v);
  http.match((r) => r.url.startsWith('/api/cases')).forEach((r) => r.flush([]));
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, http };
}

describe('Bank → Investoren (R32-I)', () => {
  it('shows the packages of an offer side by side and accepts one', () => {
    const second = contract({ id: 6, packageNo: 2, capitalType: 'SUBORDINATED', years: 2, endYear: 3,
      considerations: [obligation({ id: 12, type: 'W1', quantity: 600000, minPerYear: 200000 })] });
    const { el, http } = panel(view({ offers: [{ offer: offerCase(), extension: false, packages: [contract(), second] }] }));
    const packages = el.querySelectorAll('[data-testid="investor-package"]');
    expect(packages.length).toBe(2);
    expect(packages[0].textContent).toContain('stille Beteiligung');
    expect(packages[0].textContent).toContain('20.000 l Weizen je Monat');
    expect(packages[1].textContent).toContain('Nachrangdarlehen');
    expect(packages[1].textContent).toContain('mindestens 200.000 l je Jahr');
    (packages[1].querySelector('[data-testid="package-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/investors/offers/7/packages/6/accept').flush(second);
    http.expectOne('/api/investors').flush(view());
  });

  it('delivers milk from a chosen stable and asks for the consent to a field sale', () => {
    const w3 = obligation({ id: 21, type: 'W3', fillType: 'MILK', quantity: 5000, outstanding: 5000,
      current: { periodKey: 12, monthly: true, year: 2, required: 5000, delivered: 0, status: 'OPEN', graceUntil: null,
        compensation: null, checkedGameTime: null } });
    const p1 = obligation({ id: 22, type: 'P1', main: false, fillType: null, quantity: null, consents: [1] });
    const running = contract({ status: 'ACTIVE', considerations: [w3, p1] });
    const { el, fixture, http } = panel(view({
      contracts: [running], fields: [{ farmlandId: 1, name: 'Feld 1', hectares: 10 }, { farmlandId: 2, name: 'Feld 2', hectares: 5 }],
      stalls: [{ husbandryUniqueId: 'hus_1', animalType: 'COW', milk: [{ fillType: 'MILK', amount: 20000 }], subTypes: [] },
        { husbandryUniqueId: 'hus_2', animalType: 'PIG', milk: [], subTypes: [] }],
    }));
    expect(el.querySelector('[data-testid="consideration-progress"]')?.textContent).toContain('0 von 5.000 l');
    const deliver = el.querySelector('[data-testid="deliver"] button') as HTMLButtonElement;
    expect(deliver.disabled).toBe(true);
    const qty = el.querySelector('[data-testid="deliver-quantity"]') as HTMLInputElement;
    qty.value = '5000';
    qty.dispatchEvent(new Event('input'));
    const stall = el.querySelector('[data-testid="deliver-stall"]') as HTMLSelectElement;
    expect(stall.options.length).toBe(2); // only the stable with milk
    stall.value = 'hus_1';
    stall.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    deliver.click();
    const req = http.expectOne('/api/investors/obligations/21/deliver');
    expect(req.request.body).toEqual({ quantity: 5000, husbandryUniqueId: 'hus_1' });
    req.flush({});
    http.expectOne('/api/investors').flush(view({ contracts: [running], fields: [{ farmlandId: 1, name: 'Feld 1', hectares: 10 },
      { farmlandId: 2, name: 'Feld 2', hectares: 5 }] }));
    fixture.detectChanges();
    const field = el.querySelector('[data-testid="consent-field"]') as HTMLSelectElement;
    expect(field.options[1].disabled).toBe(true); // field 1 already agreed
    field.value = '2';
    field.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="consent-request"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/investors/contracts/5/field-consent').request.body).toEqual({ farmlandId: 2 });
  });

  it('pays a refused payment by button and names the breaches', () => {
    const running = contract({ status: 'ACTIVE', breaches: 2, payments: [
      { id: 31, kind: 'COMPENSATION', amount: 5750, year: 2, gameTime: DAY, status: 'OPEN', claimCaseId: null, note: null }],
      considerations: [obligation({ breaches: [{ periodKey: 13, monthly: true, year: 2, required: 20000, delivered: 0,
        status: 'COMPENSATED', graceUntil: 19 * DAY, compensation: 5750, checkedGameTime: 14 * DAY }] })] });
    const { el, http } = panel(view({ contracts: [running], savegameEnabled: false }));
    expect(el.querySelector('[data-testid="investors-off"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="contract-breaches"]')?.textContent).toContain('2 von 3');
    expect(el.querySelector('[data-testid="consideration-breach"]')?.textContent).toContain('Ausgleich');
    (el.querySelector('[data-testid="payment-pay"] button') as HTMLButtonElement).click();
    http.expectOne('/api/investors/payments/31/pay').flush({});
  });
});

describe('Investor cases (R32-I)', () => {
  function card(c: CaseView) {
    TestBed.configureTestingModule({ imports: [CaseCard], providers });
    const fixture = TestBed.createComponent(CaseCard);
    fixture.componentRef.setInput('c', c);
    fixture.detectChanges();
    return { el: fixture.nativeElement as HTMLElement, http: TestBed.inject(HttpTestingController) };
  }

  it('declines an offer in the case card, the packages live in the bank', () => {
    const { el, http } = card(offerCase());
    expect(el.querySelector('[data-testid="investor-offer-case"]')?.textContent).toContain('2 Paketen');
    expect(el.querySelector('[data-testid="case-accept"]')).toBeNull();
    (el.querySelector('[data-testid="case-decline"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/7/decline');
  });

  it('pays a claim like a tax bill and shows it overdue', () => {
    const { el, http } = card(offerCase({ id: 8, kind: 'INVESTOR_CLAIM', roundsUsed: 2, offerAmount: 1000000 }));
    expect(el.querySelector('[data-testid="investor-claim-overdue"]')?.textContent).toContain('2 Monat');
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/8/accept');
  });

  it('routes every investor case and the open delivery to Bank → Investoren', () => {
    for (const kind of ['INVESTOR_OFFER', 'INVESTOR_REMINDER', 'INVESTOR_CLAIM', 'INVESTOR_PURCHASE', 'INVESTOR_VISIT']) {
      expect(caseTabOf(kind)).toBe('investoren');
    }
    const due = { key: 'investor-1', type: 'INVESTOR_DUE', kind: 'W2', deadlineGameTime: DAY, gameTime: 0 } as never;
    expect(taskAppId(due)).toBe('bank');
    expect(taskTabId(due)).toBe('investoren');
  });
});
