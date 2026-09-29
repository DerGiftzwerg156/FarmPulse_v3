import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TaskView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { TranslationService } from '../../core/i18n/translation.service';
import { DAY, message, savegame } from '../../../testing/fixtures';
import { Tasks } from './tasks';
import { dueLabel, taskCategory, taskGroup } from './task-groups';
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
    expect(taskLink(task({ type: 'NEGOTIATION', negotiation: { id: 4 } as never }))).toEqual({ path: '/farmland', query: { negotiation: 4 } });
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
