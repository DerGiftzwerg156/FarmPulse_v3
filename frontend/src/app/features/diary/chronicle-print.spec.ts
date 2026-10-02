import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ChronicleView } from '../../core/api/models';
import { DAY } from '../../../testing/fixtures';
import { ChroniclePrint, PRINT } from './chronicle-print';

const CHRONICLE: ChronicleView = {
  farmName: 'Hof Lindenhain', gameTime: 12 * DAY, gameDay: 12, backstory: 'Der Hof gehörte schon dem Großvater.',
  milestones: [{ key: 'LOAN_REPAID', title: 'Erster Kredit getilgt', text: 'Der erste Bankkredit ist vollständig zurückgezahlt.', gameTime: 11 * DAY, gameDay: 11 }],
  days: [{ gameDay: 11, entries: [
    { gameTime: 11 * DAY + 8 * 3_600_000, entryType: 'PLAYER_NOTE', title: 'Regentag', text: 'Heute nur Werkstatt.', note: true },
    { gameTime: 11 * DAY + 8 * 3_600_000, entryType: 'MILESTONE', title: 'Erster Kredit getilgt', text: null, note: false },
  ] }],
  reports: [{
    year: 1, months: 12, operatingIncome: 30000, operatingExpense: -4000, operatingResult: 26000, taxStatus: 'OPEN',
    profit: null, tax: null, income: [{ label: 'Ernteverkauf', amount: 30000 }], expenses: [{ label: 'Kraftstoff', amount: -4000 }],
    fields: [{ farmlandId: 12, fruit: 'Weizen', hectares: 4.5, harvested: true, withered: false, yieldLiters: 42750 }],
  }],
};

describe('ChroniclePrint', () => {
  function setup() {
    vi.useFakeTimers();
    const print = vi.spyOn(PRINT, 'open').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      imports: [ChroniclePrint],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(ChroniclePrint);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, http, print, el: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('shows the same content as the file and opens the print dialog once loaded', () => {
    const { fixture, http, print, el } = setup();
    expect(print).not.toHaveBeenCalled();
    http.expectOne('/api/diary/chronicle/view').flush(CHRONICLE);
    fixture.detectChanges();
    vi.runAllTimers();
    expect(print).toHaveBeenCalledTimes(1);
    expect(el.querySelector('[data-testid="chronicle-title"]')?.textContent).toContain('Hofchronik – Hof Lindenhain');
    expect(el.querySelector('[data-testid="chronicle-backstory"]')?.textContent).toContain('Großvater');
    expect(el.querySelector('[data-testid="chronicle-milestone"]')?.textContent).toContain('Erster Kredit getilgt');
    const entries = el.querySelectorAll('[data-testid="chronicle-entry"]');
    expect(entries[0].textContent).toContain('08:00');
    expect(entries[0].textContent).toContain('eigene Notiz');
    expect(entries[1].textContent).toContain('Meilenstein');
    expect(el.querySelector('[data-testid="chronicle-tax"]')?.textContent).toContain('offen');
    expect(el.querySelector('[data-testid="chronicle-field"]')?.textContent).toContain('42.750 l');
    expect(el.querySelector('[data-testid="print-actions"]')?.classList).toContain('fp-no-print');
  });

  it('prints again on demand', () => {
    const { fixture, http, print, el } = setup();
    http.expectOne('/api/diary/chronicle/view').flush(CHRONICLE);
    fixture.detectChanges();
    vi.runAllTimers();
    (el.querySelector('[data-testid="print-again"]') as HTMLButtonElement).click();
    expect(print).toHaveBeenCalledTimes(2);
  });
});
