import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CaseView, ContractorQuoteView } from '../../core/api/models';
import { DAY } from '../../../testing/fixtures';
import { ContractorWorkCard } from './contractor-work-card';

const quote = (over: Partial<ContractorQuoteView> = {}): ContractorQuoteView => ({
  farmlandId: 12, fieldName: 'Feld 12', hectares: 4.5, phase: 'HARVESTABLE', selected: [], maxWorks: 3,
  doneByGameTime: 12 * DAY, openOrders: [], fruitTypes: ['WHEAT', 'BARLEY'],
  options: [
    { work: 'HARVEST', price: 810, harvestLiters: 38000, fillType: 'WHEAT', reason: null },
    { work: 'PLOW', price: 495, harvestLiters: null, fillType: null, reason: 'PHASE' },
    { work: 'SOW', price: 450, harvestLiters: null, fillType: null, reason: 'PHASE' },
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

  function tick(el: HTMLElement, work: string) {
    (el.querySelector(`[data-work="${work}"] [data-testid="contractor-select"]`) as HTMLInputElement).click();
  }

  it('lists the works with price, says when they are done and shows why a work is not possible', () => {
    const { el } = setup();
    expect(el.querySelector('[data-testid="contractor-intro"]')?.textContent).toContain('bis zu 3 Arbeiten');
    const options = el.querySelectorAll('[data-testid="contractor-option"]');
    expect(options.length).toBe(3);
    expect(options[1].textContent).toContain('Pflügen');
    expect(options[1].querySelector('[data-testid="contractor-reason"]')?.textContent).toContain('Feldzustand');
    expect((options[1].querySelector('[data-testid="contractor-select"]') as HTMLInputElement).disabled).toBe(true);
    expect(options[0].querySelector('[data-testid="contractor-liters"]')?.textContent).toContain('Weizen');
    expect((options[0].querySelector('[data-testid="contractor-select"]') as HTMLInputElement).disabled).toBe(false);
    expect((el.querySelector('[data-testid="contractor-order"] button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('ticks several works, rechecks the others with them and orders them together', () => {
    const { el, fixture, http } = setup();
    let emitted = 0;
    fixture.componentInstance.ordered.subscribe(() => emitted++);
    tick(el, 'HARVEST');
    // after the harvest the field can be cultivated and sown
    http.expectOne('/api/contractor-work/fields/12?works=HARVEST').flush(quote({ selected: ['HARVEST'], options: [
      { work: 'HARVEST', price: 810, harvestLiters: 38000, fillType: 'WHEAT', reason: null },
      { work: 'CULTIVATE', price: 360, harvestLiters: null, fillType: null, reason: null },
      { work: 'SOW', price: 450, harvestLiters: null, fillType: null, reason: 'PHASE' },
    ] }));
    fixture.detectChanges();
    tick(el, 'CULTIVATE');
    http.expectOne('/api/contractor-work/fields/12?works=HARVEST&works=CULTIVATE').flush(quote({
      selected: ['HARVEST', 'CULTIVATE'], options: [
        { work: 'HARVEST', price: 810, harvestLiters: 38000, fillType: 'WHEAT', reason: null },
        { work: 'CULTIVATE', price: 360, harvestLiters: null, fillType: null, reason: null },
        { work: 'SOW', price: 450, harvestLiters: null, fillType: null, reason: null },
      ] }));
    fixture.detectChanges();
    tick(el, 'SOW');
    http.expectOne('/api/contractor-work/fields/12?works=HARVEST&works=CULTIVATE&works=SOW').flush(quote({
      selected: ['HARVEST', 'CULTIVATE', 'SOW'], options: [
        { work: 'HARVEST', price: 810, harvestLiters: 38000, fillType: 'WHEAT', reason: null },
        { work: 'CULTIVATE', price: 360, harvestLiters: null, fillType: null, reason: null },
        { work: 'SOW', price: 450, harvestLiters: null, fillType: null, reason: null },
        { work: 'FERTILIZE', price: 315, harvestLiters: null, fillType: null, reason: 'MAX_WORKS' },
      ] }));
    fixture.detectChanges();
    expect(el.querySelector('[data-work="FERTILIZE"] [data-testid="contractor-reason"]')?.textContent).toContain('voll');
    expect(el.querySelector('[data-testid="contractor-total"]')?.textContent).toContain('3 Arbeiten');
    const select = el.querySelector('[data-testid="contractor-fruit"]') as HTMLSelectElement;
    select.value = 'BARLEY';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="contractor-order"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/contractor-work');
    expect(req.request.body).toEqual({ farmlandId: 12, works: ['HARVEST', 'CULTIVATE', 'SOW'], fruitType: 'BARLEY' });
    req.flush([order(), order({ id: 42, reference: 'CULTIVATE', offerAmount: 360 }),
      order({ id: 43, reference: 'SOW', offerAmount: 450, title: 'BARLEY' })]);
    http.expectOne('/api/contractor-work/fields/12').flush(quote({ openOrders: [order(),
      order({ id: 42, reference: 'CULTIVATE', offerAmount: 360 }), order({ id: 43, reference: 'SOW', offerAmount: 450 })] }));
    fixture.detectChanges();
    expect(emitted).toBe(1);
    const open = el.querySelector('[data-testid="contractor-open"]')?.textContent ?? '';
    expect(open).toContain('Ernten, Grubbern, Säen');
    expect(open).toContain('1.620');
    expect(el.querySelector('[data-testid="contractor-info"]')).not.toBeNull();
  });

  it('unticking a work reloads the form without it', () => {
    const { el, fixture, http } = setup();
    tick(el, 'HARVEST');
    http.expectOne('/api/contractor-work/fields/12?works=HARVEST').flush(quote({ selected: ['HARVEST'] }));
    fixture.detectChanges();
    expect((el.querySelector('[data-testid="contractor-order"] button') as HTMLButtonElement).disabled).toBe(false);
    tick(el, 'HARVEST');
    http.expectOne('/api/contractor-work/fields/12').flush(quote());
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="contractor-total"]')).toBeNull();
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
