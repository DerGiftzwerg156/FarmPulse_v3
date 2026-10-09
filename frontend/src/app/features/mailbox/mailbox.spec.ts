import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { character, message } from '../../../testing/fixtures';
import { Mailbox } from './mailbox';

describe('Mailbox', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [Mailbox],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Mailbox);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const el = fixture.nativeElement as HTMLElement;
    const flushSavegame = () => http.match('/api/savegame').forEach((r) => r.flush(null));
    return { fixture, http, el, store: TestBed.inject(GameStateStore), flushSavegame };
  }

  const credit = message({ id: 1, subject: 'Ihr Kreditantrag', body: 'Wir können Ihnen ein Gegenangebot machen.', formLink: '/bank?application=5' });
  const invite = message({
    id: 2, subject: 'Einladung zum Erntefest', category: 'VILLAGE_LIFE', read: true, gameTime: 50,
    character: character({ id: 3, name: 'Gerd Albers', role: 'VILLAGER' }),
  });

  it('lists threads, highlights unread ones and marks village-life mails', () => {
    const { http, el, fixture } = setup();
    http.expectOne('/api/mails').flush([credit, invite]);
    fixture.detectChanges();
    const rows = el.querySelectorAll('[data-testid="mail-thread"]');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('[data-testid="unread-dot"]')).not.toBeNull();
    expect(rows[1].querySelector('[data-testid="unread-dot"]')).toBeNull();
    expect(rows[1].querySelector('[data-testid="village-life-badge"]')?.textContent).toContain('Dorfleben');
    expect(rows[0].querySelector('[data-testid="village-life-badge"]')).toBeNull();
  });

  it('filters unread threads', () => {
    const { http, el, fixture } = setup();
    http.expectOne('/api/mails').flush([credit, invite]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="filter-unread"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="mail-thread"]').length).toBe(1);
  });

  it('marks the unread mails of the active filter as read after confirmation', () => {
    const { http, el, fixture, flushSavegame } = setup();
    const gossip = message({ id: 4, subject: 'Neues aus dem Dorf', category: 'VILLAGE_LIFE', gameTime: 40 });
    http.expectOne('/api/mails').flush([credit, invite, gossip]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="filter-village-life"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    (el.querySelector('[data-testid="mark-all-read"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mark-all-read-text"]')?.textContent).toContain('1');
    (el.querySelector('[data-testid="mark-all-read-confirm"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/mails/read');
    expect(req.request.body).toEqual({ ids: [4] });
    req.flush({ marked: 1 });
    flushSavegame();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mark-all-read-text"]')).toBeNull();
    (el.querySelector('[data-testid="filter-all"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const rows = el.querySelectorAll('[data-testid="mail-thread"]');
    // the credit mail is outside the village-life filter and stays unread
    expect(rows[0].querySelector('[data-testid="unread-dot"]')).not.toBeNull();
    expect([...rows].filter((r) => r.querySelector('[data-testid="unread-dot"]')).length).toBe(1);
  });

  it('cancels marking all as read without a request and disables the button without unread mails', () => {
    const { http, el, fixture } = setup();
    http.expectOne('/api/mails').flush([credit, invite]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="mark-all-read"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    (el.querySelector('[data-testid="modal-close"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mark-all-read-text"]')).toBeNull();
    http.expectNone('/api/mails/read');
    (el.querySelector('[data-testid="filter-village-life"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect((el.querySelector('[data-testid="mark-all-read"] button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('keeps the dialog open and shows the error when marking all as read fails', () => {
    const { http, el, fixture } = setup();
    http.expectOne('/api/mails').flush([credit]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="mark-all-read"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    (el.querySelector('[data-testid="mark-all-read-confirm"] button') as HTMLButtonElement).click();
    http.expectOne('/api/mails/read').flush({ code: 'X', message: 'Speichern fehlgeschlagen', fields: {} }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mark-all-read-error"]')?.textContent).toContain('Speichern fehlgeschlagen');
    expect(el.querySelector('[data-testid="mail-thread"] [data-testid="unread-dot"]')).not.toBeNull();
  });

  it('reloads the list on a live mail event', () => {
    const { http, el, fixture, store } = setup();
    http.expectOne('/api/mails').flush([invite]);
    fixture.detectChanges();
    store.mailVersion.update((v) => v + 1);
    fixture.detectChanges();
    http.expectOne('/api/mails').flush([credit, invite]);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="mail-thread"]').length).toBe(2);
  });

  it('opens a thread, marks it read and shows a form link instead of a free reply where a form is required', () => {
    const { http, el, fixture, flushSavegame } = setup();
    http.expectOne('/api/mails').flush([credit, invite]);
    fixture.detectChanges();
    (el.querySelectorAll('[data-testid="mail-thread"]')[0] as HTMLButtonElement).click();
    http.expectOne('/api/mails/1').flush({ message: credit, thread: [credit] });
    flushSavegame();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mail-detail"]')?.textContent).toContain('Gegenangebot');
    const link = el.querySelector('[data-testid="form-link"] a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/bank?application=5');
    expect(el.querySelector('[data-testid="reply-form"]')).toBeNull();
    expect(el.querySelectorAll('[data-testid="mail-thread"]')[0].querySelector('[data-testid="unread-dot"]')).toBeNull();
  });

  it('shows the open decision of a mail right below it, with the same actions as in the app', () => {
    const { http, el, fixture, flushSavegame } = setup();
    http.expectOne('/api/mails').flush([credit, invite]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="filter-decision"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="mail-thread"]').length).toBe(1);
    (el.querySelectorAll('[data-testid="mail-thread"]')[0] as HTMLButtonElement).click();
    http.expectOne('/api/mails/1').flush({ message: credit, thread: [credit] });
    flushSavegame();
    fixture.detectChanges();
    TestBed.inject(TasksStore).items.set([{ key: 'credit-5', type: 'CREDIT_COUNTER', kind: 'COUNTER_OFFER', deadlineGameTime: null, gameTime: 0,
      serviceCase: null, contract: null, call: null, negotiation: null, marketEvent: null, posting: null, pendingApplicants: null,
      application: { id: 5, amount: 80000, purpose: 'Stall', termMonths: 36, status: 'DECIDED', submittedAtGameTime: 0,
        decisionVisibleAtGameTime: 0, decision: 'COUNTER_OFFER', reasonCategory: null, offeredAmount: 60000, offeredTermMonths: 36,
        offeredInterestRatePercent: 4.9, loanId: null } }]);
    fixture.detectChanges();
    const decision = el.querySelector('[data-testid="mail-decision"]');
    expect(decision?.textContent).toContain('Gegenangebot der Bank');
    expect(el.querySelector('[data-testid="form-link"]')).toBeNull();
    (decision!.querySelector('[data-testid="task-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/credit-applications/5/accept-counter');
  });

  it('sends a free reply and appends it to the thread', async () => {
    const { http, el, fixture } = setup();
    http.expectOne('/api/mails').flush([invite]);
    fixture.detectChanges();
    (el.querySelector('[data-testid="mail-thread"]') as HTMLButtonElement).click();
    http.expectOne('/api/mails/2').flush({ message: invite, thread: [invite] });
    fixture.detectChanges();
    await fixture.whenStable();
    const ta = el.querySelector('[data-testid="reply-text"]') as HTMLTextAreaElement;
    ta.value = 'Ich komme gern!';
    ta.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const btn = el.querySelector('[data-testid="reply-send"] button') as HTMLButtonElement;
    btn.click();
    const req = http.expectOne('/api/mails/2/reply');
    expect(req.request.body).toEqual({ text: 'Ich komme gern!' });
    req.flush(message({ id: 9, threadRootId: 2, initiatedBy: 'PLAYER', body: 'Ich komme gern!', read: true, gameTime: 60 }));
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="thread-message"]').length).toBe(2);
    expect(el.querySelector('[data-testid="mail-detail"]')?.textContent).toContain('Du');
  });

  it('opens a mail directly via ?id=', () => {
    const { http, el, fixture } = setup();
    fixture.componentRef.setInput('id', '2');
    fixture.detectChanges();
    http.expectOne('/api/mails').flush([invite]);
    http.expectOne('/api/mails/2').flush({ message: invite, thread: [invite] });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="mail-detail"]')?.textContent).toContain('Erntefest');
  });

  it('offers the onboarding when no savegame is active', () => {
    const { http, el, fixture } = setup();
    http.expectOne('/api/mails').flush({ code: 'NO_ACTIVE_SAVEGAME', message: 'Kein aktiver Spielstand', fields: {} }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="no-savegame"]')).not.toBeNull();
  });
});
