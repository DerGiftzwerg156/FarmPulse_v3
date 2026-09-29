import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { CalendarApp } from './calendar';

const NOW = 47 * DAY + 11.5 * 3_600_000;

describe('CalendarApp', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [CalendarApp],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({ gameTime: NOW, gameDay: 47, balance: 212956 }));
    const fixture = TestBed.createComponent(CalendarApp);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/calendar').flush({
      gameTime: NOW, currentPeriod: 3, year: 2, daysPerPeriod: 1, nextMonthStart: 48 * DAY, nextPeriod: 4,
      agenda: [{ gameTime: 48 * DAY, kind: 'MONTH_START', subKind: null, title: null, amount: null, reference: '4' },
        { gameTime: 48 * DAY, kind: 'FESTIVAL', subKind: 'SHOOTING_CLUB', title: null, amount: null, reference: 'SCHUETZENFEST' }],
      monthStartDebits: [{ kind: 'SALARIES', subKind: null, label: null, amount: 5580, count: 2 },
        { kind: 'CONTRACT', subKind: 'LEASE', label: '8', amount: 410, count: 1 }],
      monthStartTotal: 5990,
      yearEvents: [{ period: 4, kind: 'FESTIVAL', reference: 'SCHUETZENFEST' }, { period: 1, kind: 'TAX_ASSESSMENT', reference: null }],
    });
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the next days with fixed dates and task deadlines', () => {
    const { fixture, http, el } = setup();
    http.match('/api/tasks').forEach((r) => r.flush({ waitingPrompts: 0, items: [{ key: 'case-1', type: 'CASE', kind: 'TAX_BILL',
      deadlineGameTime: 49 * DAY, gameTime: 0, serviceCase: { id: 1, kind: 'TAX_BILL', title: 'Nachzahlung', offerAmount: 4312, costAmount: 0 },
      contract: null, application: null, call: null, negotiation: null, marketEvent: null, posting: null, pendingApplicants: null }] }));
    http.match('/api/notices').forEach((r) => r.flush([]));
    fixture.detectChanges();
    const days = el.querySelectorAll('[data-testid="agenda-day"]');
    expect(days.length).toBe(2);
    expect(days[0].textContent).toContain('Monatsbeginn Juni');
    expect(days[0].textContent).toContain('Schützenfest');
    expect(days[1].textContent).toContain('Frist: Steuerbescheid');
    expect(el.querySelector('[data-testid="self-pay"]')?.textContent).toContain('4.312');
  });

  it('lists the month start debits and the year', () => {
    const { fixture, el } = setup();
    fixture.detectChanges();
    const debits = el.querySelectorAll('[data-testid="debit"]');
    expect(debits[0].textContent).toContain('Gehälter (2)');
    expect(debits[1].textContent).toContain('Pacht · Feld 8');
    expect(el.querySelector('[data-testid="debit-total"]')?.textContent?.replace(/\s/g, ' ')).toContain('5.990 €');
    expect(el.querySelector('[data-testid="month-start"]')?.textContent?.replace(/\s/g, ' ')).toContain('ungefähr 206.966 €');
    const months = el.querySelectorAll('[data-testid="year-month"]');
    expect(months.length).toBe(12);
    expect(months[3].textContent).toContain('Schützenfest');
    expect(months[0].textContent).toContain('Steuerbescheid');
  });
});
