import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CaseView, ContractorQuoteView } from '../../core/api/models';
import { DAY } from '../../../testing/fixtures';
import { ContractorWorkCard } from './contractor-work-card';

const quote = (over: Partial<ContractorQuoteView> = {}): ContractorQuoteView => ({
  farmlandId: 12, fieldName: 'Feld 12', hectares: 4.5, phase: 'HARVESTABLE', daysMin: 1, daysMax: 3, openOrder: null,
  fruitTypes: ['WHEAT', 'BARLEY'],
  options: [
    { work: 'PLOW', price: 495, harvestLiters: null, fillType: null, reason: 'PHASE' },
    { work: 'SOW', price: 450, harvestLiters: null, fillType: null, reason: 'PHASE' },
    { work: 'HARVEST', price: 810, harvestLiters: 38000, fillType: 'WHEAT', reason: null },
  ],
  ...over,
});

const order = (over: Partial<CaseView> = {}): CaseView => ({
  id: 41, kind: 'CONTRACTOR_WORK', status: 'IN_PROGRESS', character: null, farmlandId: 12, hectares: 4.5, damageAmount: null,
  payoutAmount: null, costAmount: null, offerAmount: 810, roundsUsed: 0, measureAgreed: false, reference: 'HARVEST',
  gameTime: 10 * DAY, deadlineGameTime: 12 * DAY, resolution: null, quantity: 38000, title: 'WHEAT', ...over,
});

describe('ContractorWorkCard', () => {
  function setup(q: ContractorQuoteView = quote()) {
    TestBed.configureTestingModule({ imports: [ContractorWorkCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(ContractorWorkCard);
    fixture.componentRef.setInput('farmlandId', 12);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/contractor-work/fields/12').flush(q);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('lists the works with price and shows why a work is not possible', () => {
    const { el } = setup();
    const options = el.querySelectorAll('[data-testid="contractor-option"]');
    expect(options.length).toBe(3);
    expect(options[0].textContent).toContain('Pflügen');
    expect(options[0].querySelector('[data-testid="contractor-reason"]')?.textContent).toContain('Feldzustand');
    expect(options[0].querySelector('[data-testid="contractor-order"]')).toBeNull();
    expect(options[2].querySelector('[data-testid="contractor-liters"]')?.textContent).toContain('Weizen');
    expect(options[2].querySelector('[data-testid="contractor-order"]')).not.toBeNull();
  });

  it('orders the harvest and reloads the form with the open order', () => {
    const { el, fixture, http } = setup();
    let emitted = 0;
    fixture.componentInstance.ordered.subscribe(() => emitted++);
    (el.querySelector('[data-work="HARVEST"] [data-testid="contractor-order"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/contractor-work');
    expect(req.request.body).toEqual({ farmlandId: 12, work: 'HARVEST', fruitType: null });
    req.flush(order());
    http.expectOne('/api/contractor-work/fields/12').flush(quote({ openOrder: order() }));
    fixture.detectChanges();
    expect(emitted).toBe(1);
    expect(el.querySelector('[data-testid="contractor-open"]')?.textContent).toContain('Ernten');
    expect(el.querySelector('[data-testid="contractor-info"]')).not.toBeNull();
  });

  it('sends the chosen fruit type with the sowing', () => {
    const q = quote({ phase: 'EMPTY', options: [{ work: 'SOW', price: 450, harvestLiters: null, fillType: null, reason: null }] });
    const { el, fixture, http } = setup(q);
    const select = el.querySelector('[data-testid="contractor-fruit"]') as HTMLSelectElement;
    select.value = 'BARLEY';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="contractor-order"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/contractor-work').request.body).toEqual({ farmlandId: 12, work: 'SOW', fruitType: 'BARLEY' });
  });

  it('shows the reason when the field cannot be worked (no field data from the mod)', () => {
    TestBed.configureTestingModule({ imports: [ContractorWorkCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(ContractorWorkCard);
    fixture.componentRef.setInput('farmlandId', 12);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/contractor-work/fields/12')
      .flush({ code: 'NO_FIELD_DATA', message: 'Der Mod meldet keine Felddaten.' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="contractor-unavailable"]')?.textContent)
      .toContain('Felddaten');
  });
});
