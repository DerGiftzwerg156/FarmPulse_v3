import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GrantStatusView, GrantView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { InvestmentGrantCard } from './investment-grant-card';

const grant = (over: Partial<GrantView> = {}): GrantView => ({
  id: 5, kind: 'MACHINE', status: 'APPROVED', plannedSum: 100000, appliedGameTime: DAY, approvalDueGameTime: 11 * DAY,
  approvedGameTime: 11 * DAY, purchaseDeadlineGameTime: 17 * DAY, recognisedSum: 80000, grantAmount: null, paidGameTime: null,
  bindingEndsGameTime: null, repaidAmount: 0, objects: [], ...over,
});

const status = (grants: GrantView[] = []): GrantStatusView => ({
  enabled: true, minSum: 10000, grantPercent: 30, grantMax: 50000, purchaseMonths: 6, bindingMonths: 24, processingDays: 10,
  grants,
});

describe('InvestmentGrantCard', () => {
  function setup(s: GrantStatusView) {
    TestBed.configureTestingModule({ imports: [InvestmentGrantCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(InvestmentGrantCard);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/investment-grants').flush(s);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('applies for a grant with kind and planned sum', () => {
    const { el, fixture, http } = setup(status());
    const kind = el.querySelector('[data-testid="grant-kind"]') as HTMLSelectElement;
    kind.value = 'BUILDING';
    kind.dispatchEvent(new Event('change'));
    const sum = el.querySelector('[data-testid="grant-sum"]') as HTMLInputElement;
    sum.value = '60000';
    sum.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="grant-apply"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/investment-grants').request.body).toEqual({ kind: 'BUILDING', plannedSum: 60000 });
  });

  it('submits the proof of an approved grant and shows a paid one with its binding', () => {
    const { el, http } = setup(status([grant(), grant({ id: 6, status: 'PAID', grantAmount: 30000, bindingEndsGameTime: 35 * DAY,
      repaidAmount: 21750, objects: [{ vehicleUniqueId: 'veh_00077', value: 80000, soldGameTime: 13 * DAY, repayment: 21750 }] })]));
    (el.querySelector('[data-testid="grant-proof"] button') as HTMLButtonElement).click();
    http.expectOne('/api/investment-grants/5/proof');
    expect(el.querySelector('[data-testid="grant-paid"]')?.textContent).toContain('30.000');
    expect(el.querySelector('[data-testid="grant-repaid"]')).not.toBeNull();
  });
});
