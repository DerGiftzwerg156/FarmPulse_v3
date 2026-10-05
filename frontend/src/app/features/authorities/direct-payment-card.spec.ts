import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DirectPaymentStatusView, DirectPaymentView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { DirectPaymentCard } from './direct-payment-card';

const application = (over: Partial<DirectPaymentView> = {}): DirectPaymentView => ({
  id: 3, cropYear: 2, status: 'OPEN', openedGameTime: 10 * DAY, deadlineGameTime: 13 * DAY, lateLimitGameTime: 38 * DAY,
  submittedGameTime: null, lateDays: 0, checkStatus: 'NONE', deviatingHectares: null, deviationCut: null, rotationCut: null,
  lateCut: null, premium: null, paidAmount: null, fields: [], ...over,
});

const status = (over: Partial<DirectPaymentStatusView> = {}): DirectPaymentStatusView => ({
  enabled: true, premiumPerHa: 250, lateCutPercentPerDay: 1, lateMaxDays: 25, crops: ['WHEAT', 'BARLEY', 'BRACHE'],
  form: [
    { farmlandId: 12, fieldName: '12', hectares: 4.5, suggestedCrop: 'WHEAT' },
    { farmlandId: 13, fieldName: '13', hectares: 3, suggestedCrop: 'BRACHE' },
  ],
  applications: [application()], ...over,
});

describe('DirectPaymentCard', () => {
  function setup(s: DirectPaymentStatusView = status()) {
    TestBed.configureTestingModule({ imports: [DirectPaymentCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(DirectPaymentCard);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/direct-payment').flush(s);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('prefills the form with the current crops and submits the corrections', () => {
    const { el, fixture, http } = setup();
    const fields = el.querySelectorAll('[data-testid="direct-payment-field"]');
    expect(fields.length).toBe(2);
    const select = fields[1].querySelector('[data-testid="direct-payment-crop"]') as HTMLSelectElement;
    expect(select.value).toBe('BRACHE');
    select.value = 'BARLEY';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="direct-payment-submit"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/direct-payment/3/submit');
    expect(req.request.body).toEqual({ fields: [{ farmlandId: 12, crop: 'WHEAT' }, { farmlandId: 13, crop: 'BARLEY' }] });
  });

  it('leaves out unchecked fields', () => {
    const { el, fixture, http } = setup();
    const box = el.querySelectorAll('[data-testid="direct-payment-include"]')[0] as HTMLInputElement;
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="direct-payment-submit"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/direct-payment/3/submit').request.body).toEqual({ fields: [{ farmlandId: 13, crop: 'BRACHE' }] });
  });

  it('shows the check result and the paid premium of a past year', () => {
    const paid = application({
      status: 'PAID', checkStatus: 'DONE', deviationCut: 1688, rotationCut: 0, premium: 2250, paidAmount: 562,
      fields: [{ farmlandId: 13, fieldName: '13', hectares: 4.5, declaredCrop: 'BARLEY', actualCrop: 'WHEAT', rotationRepeat: false }],
    });
    const { el } = setup(status({ form: [], applications: [paid] }));
    expect(el.querySelector('[data-testid="direct-payment-form"]')).toBeNull();
    expect(el.querySelector('[data-testid="direct-payment-deviation"]')?.textContent).toContain('Weizen');
    expect(el.querySelector('[data-testid="direct-payment-check"]')?.textContent).toContain('Kürzung');
    expect(el.querySelector('[data-testid="direct-payment-paid"]')).not.toBeNull();
  });
});
