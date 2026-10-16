import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TaskView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { TranslationService } from '../../core/i18n/translation.service';
import { DAY, message, savegame } from '../../../testing/fixtures';
import { Tasks } from './tasks';
import { dueLabel, taskCategory, taskGroup, taskTitle } from './task-groups';
import { taskAppId, taskLink } from '../../layout/task-apps';

const NOW = 47 * DAY + 11.5 * 3_600_000;

function task(over: Partial<TaskView>): TaskView {
  return {
    key: 'x', type: 'CASE', kind: null, deadlineGameTime: null, gameTime: 0, serviceCase: null, contract: null, application: null,
    call: null, negotiation: null, marketEvent: null, posting: null, pendingApplicants: null, ...over,
  };
}

const caseView = (id: number, kind: string, deadline: number | null) => ({
  id, kind, status: 'AWAITING_PLAYER', character: { id: 9, name: 'Lukas Oltmanns', role: 'CLUB_CHAIR', status: 'ACTIVE' },
  farmlandId: null, hectares: null, damageAmount: null, payoutAmount: null, costAmount: null, offerAmount: null, roundsUsed: 0,
  measureAgreed: false, reference: 'SCHUETZENFEST', gameTime: 40 * DAY, deadlineGameTime: deadline, resolution: null,
  measureCost: null, quantity: null, direction: null, baselineCount: null, title: null, tiers: null,
});

describe('task helpers', () => {
  it('groups by deadline: today, this week, later', () => {
    expect(taskGroup(task({ deadlineGameTime: 47 * DAY + 18 * 3_600_000 }), NOW)).toBe('today');
    expect(taskGroup(task({ type: 'CALL' }), NOW)).toBe('today');
    expect(taskGroup(task({ deadlineGameTime: 50 * DAY }), NOW)).toBe('week');
    expect(taskGroup(task({ deadlineGameTime: 62 * DAY }), NOW)).toBe('later');
    expect(taskGroup(task({ deadlineGameTime: null }), NOW)).toBe('later');
  });

  it('labels the deadline in game time', () => {
    const i18n = TestBed.inject(TranslationService);
    expect(dueLabel(task({ deadlineGameTime: 47 * DAY + 18 * 3_600_000 }), NOW, i18n)).toBe('Heute 18:00');
    expect(dueLabel(task({ deadlineGameTime: 49 * DAY }), NOW, i18n)).toBe('Tag 49');
    expect(dueLabel(task({}), NOW, i18n)).toBe('offen');
  });

  it('maps every task to its app and a highlighting link', () => {
    expect(taskAppId(task({ type: 'CREDIT_COUNTER' }))).toBe('bank');
    expect(taskAppId(task({ type: 'CALL' }))).toBe('phone');
    expect(taskAppId(task({ type: 'CASE', kind: 'INVITATION' }))).toBe('calendar');
    expect(taskCategory(task({ type: 'CREDIT_COUNTER' }))).toBe('money');
    // the link opens the tab of the entry (owner decision 2026-10-06)
    expect(taskLink(task({ type: 'NEGOTIATION', negotiation: { id: 4 } as never }))).toEqual({ path: '/farmland/verhandlungen', query: { negotiation: 4 } });
    expect(taskLink(task({ type: 'CASE', kind: 'TAX_BILL', serviceCase: { id: 7 } as never }))).toEqual({ path: '/aemter/finanzamt', query: { case: 7 } });
    expect(taskLink(task({ type: 'CALL', call: { id: 2 } as never }))).toEqual({ path: '/calls', query: { id: 2 } });
  });

  // Roadmap V4 R4-F4: a food group that runs out opens the tab "Futter"
  it('names a feed task by group and days and links it to the feed tab', () => {
    const i18n = TestBed.inject(TranslationService);
    const feed = task({ type: 'FEED', kind: 'RED', feedDue: { animalType: 'COW', title: 'Silage', kind: 'FOOD', reachDays: 6.7, fillTypes: ['SILAGE'] } });
    expect(taskTitle(feed, i18n)).toBe('Futter: Silage reicht noch 6 Tage');
    expect(taskTitle(task({ type: 'FEED', feedDue: { animalType: 'COW', title: 'STRAW', kind: 'BEDDING', reachDays: 2, fillTypes: ['STRAW'] } }), i18n))
      .toBe('Futter: Einstreu (Stroh) reicht noch 2 Tage');
    expect(taskAppId(feed)).toBe('stable');
    expect(taskLink(feed)).toEqual({ path: '/stall/futter', query: {} });
  });

  // Roadmap V4 R4-A5: a planned field in its sowing window opens the tab "Anbauplanung"
  it('names a sowing task by field and crop and links it to the crop planner', () => {
    const i18n = TestBed.inject(TranslationService);
    const sow = task({ type: 'SOW', kind: 'WHEAT', sowDue: { farmlandId: 12, fieldName: '12', fruitType: 'WHEAT', fillType: 'WHEAT' } });
    expect(taskTitle(sow, i18n)).toBe('Feld 12: WHEAT säen');
    expect(taskAppId(sow)).toBe('fieldbook');
    expect(taskLink(sow)).toEqual({ path: '/feldbuch/anbauplanung', query: {} });
  });

  // Roadmap vehicle dealer RFH-L5 (owner decision 2026-10-16): a delivery waiting at the dealer opens "Bestellungen"
  it('names a waiting delivery and a machine negotiation and links them to the dealer', () => {
    const i18n = TestBed.inject(TranslationService);
    const waiting = task({ type: 'DEALER_DELIVERY', kind: 'TRADE_IN_ATTACHED', deliveryWaiting: { deliveryId: 4, orderId: 2,
      machines: 'Fendt 700 Vario', reason: 'Fendt 312 ist angekoppelt' } });
    expect(taskTitle(waiting, i18n)).toBe('Lieferung wartet: Fendt 700 Vario – Fendt 312 ist angekoppelt');
    expect(taskAppId(waiting)).toBe('dealer');
    expect(taskLink(waiting)).toEqual({ path: '/haendler/bestellungen', query: {} });
    const offer = task({ type: 'NEGOTIATION', kind: 'DIRECT', negotiation: { id: 30, assetType: 'MACHINE_ORDER', assetId: '7' } as never });
    expect(taskTitle(offer, i18n)).not.toContain('Feld');
    expect(taskLink(offer)).toEqual({ path: '/haendler/bestellungen', query: { negotiation: 30 } });
  });
});

describe('Tasks', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [Tasks],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({ gameTime: NOW, gameDay: 47 }));
    const fixture = TestBed.createComponent(Tasks);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  function flush(http: HttpTestingController) {
    http.match('/api/tasks').forEach((r) => r.flush({
      waitingPrompts: 2,
      items: [
        task({ key: 'call-3', type: 'CALL', kind: 'RINGING', deadlineGameTime: NOW + 3_600_000,
          call: message({ id: 3, channel: 'CALL', callStatus: 'RINGING', subject: 'Bewerbung' }) }),
        task({ key: 'case-5', type: 'CASE', kind: 'INVITATION', deadlineGameTime: 49 * DAY, serviceCase: caseView(5, 'INVITATION', 49 * DAY) as never }),
      ],
    }));
    http.match('/api/notices').forEach((r) => r.flush([]));
    http.match('/api/diary').forEach((r) => r.flush([{ id: 1, gameTime: NOW - 1000, gameDay: 47, entryType: 'AUTO', category: 'CREDIT', title: 'Kredit angenommen', text: null }]));
  }

  it('groups the open decisions and shows the waiting questions of the game', () => {
    const { fixture, http, el } = setup();
    flush(http);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="task-group-today"] [data-testid="task"]').length).toBe(1);
    expect(el.querySelectorAll('[data-testid="task-group-week"] [data-testid="task"]').length).toBe(1);
    expect(el.querySelector('[data-testid="task-group-week"] [data-testid="task-app"]')?.textContent).toContain('Kalender');
    expect(el.querySelector('[data-testid="waiting-prompts"]')).toBeNull();
    // the questions in the game and today's diary are in the tab "Meldungen" (owner decision 2026-10-06)
    fixture.componentRef.setInput('tab', 'meldungen');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="task"]')).toBeNull();
    expect(el.querySelector('[data-testid="waiting-prompts"]')?.textContent).toContain('2 Frage(n)');
    expect(el.querySelector('[data-testid="today-diary"]')?.textContent).toContain('Kredit angenommen');
  });

  it('filters by category and acts on the existing endpoint', () => {
    const { fixture, http, el } = setup();
    flush(http);
    fixture.detectChanges();
    (el.querySelector('[data-testid="task-filter-today"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="task"]').length).toBe(1);
    (el.querySelector('[data-testid="task-filter-all"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/5/accept').flush(caseView(5, 'INVITATION', 49 * DAY));
    // the list reloads after a decision
    expect(http.match('/api/tasks').length).toBe(1);
  });
});
