import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { MessageView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { message } from '../../../testing/fixtures';
import { Calls, SOFT_WINDOW_SECONDS } from './calls';

describe('Calls', () => {
  const call = (id: number, status: MessageView['callStatus'], over: Partial<MessageView> = {}) =>
    message({ id, channel: 'CALL', callStatus: status, subject: `Anruf ${id}`, gameTime: id * 1000, read: true, ...over });

  function setup(list: MessageView[], id?: string) {
    TestBed.configureTestingModule({
      imports: [Calls],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Calls);
    if (id) fixture.componentRef.setInput('id', id);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/calls').flush(list);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const btn = (tid: string) => el.querySelector(`[data-testid="${tid}"] button`) as HTMLButtonElement;
    return { fixture, http, el, btn, store: TestBed.inject(GameStateStore) };
  }

  afterEach(() => vi.useRealTimers());

  it('shows every call status in the log', () => {
    const { el } = setup([call(1, 'RINGING'), call(2, 'ACCEPTED'), call(3, 'DECLINED'), call(4, 'MISSED'), call(5, 'COMPLETED')]);
    const labels = [...el.querySelectorAll('[data-testid="call-status"]')].map((b) => b.textContent?.trim());
    expect(labels).toEqual(['Klingelt', 'Angenommen', 'Abgelehnt', 'Verpasst', 'Beendet']);
  });

  it('RINGING: can be accepted from the detail view', () => {
    const { el, fixture, http, btn } = setup([call(1, 'RINGING')], '1');
    expect(el.querySelector('[data-testid="state-ringing"]')).not.toBeNull();
    btn('detail-accept').click();
    http.expectOne('/api/calls/1/accept').flush(call(1, 'ACCEPTED'));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="state-accepted"]')).not.toBeNull();
  });

  it('ACCEPTED: shows the conversation, a soft time window without consequences, talking and hanging up', async () => {
    vi.useFakeTimers();
    const opening = call(2, 'ACCEPTED', { body: 'Hallo, hier ist Heike.' });
    const { el, fixture, http, btn } = setup([opening]);
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="call-line"]')?.textContent).toContain('Hallo, hier ist Heike.');
    expect(el.querySelector('[data-testid="soft-window"]')?.textContent).toContain(`${SOFT_WINDOW_SECONDS} s`);
    vi.advanceTimersByTime(SOFT_WINDOW_SECONDS * 1000 + 1000);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="soft-window"]')?.textContent).toContain('Lass dir ruhig Zeit');
    expect(el.querySelector('[data-testid="state-accepted"]')).not.toBeNull(); // no hard timeout

    const ta = el.querySelector('[data-testid="call-text"]') as HTMLTextAreaElement;
    ta.value = 'Worum geht es?';
    ta.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    btn('call-say').click();
    const req = http.expectOne('/api/calls/2/message');
    expect(req.request.body).toEqual({ text: 'Worum geht es?' });
    req.flush(message({ id: 9, channel: 'CALL', threadRootId: 2, initiatedBy: 'PLAYER', body: 'Worum geht es?', gameTime: 3000 }));
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="call-line"]').length).toBe(2);
    expect(el.querySelector('[data-testid="soft-window"]')?.textContent).toContain(`${SOFT_WINDOW_SECONDS} s`);

    btn('hang-up').click();
    http.expectOne('/api/calls/2/complete').flush(call(2, 'COMPLETED', { body: 'Hallo, hier ist Heike.' }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="state-completed"]')).not.toBeNull();
  });

  it('DECLINED and MISSED: explain the consequences and offer a call-back', () => {
    const s = setup([call(3, 'DECLINED'), call(4, 'MISSED')], '3');
    expect(s.el.querySelector('[data-testid="state-declined"]')?.textContent).toContain('Vertrauen');
    expect(s.el.querySelector('[data-testid="call-back"]')?.getAttribute('href')).toBe('/village?character=1');
    (s.el.querySelectorAll('[data-testid="call-row"]')[1] as HTMLButtonElement).click();
    s.fixture.detectChanges();
    expect(s.el.querySelector('[data-testid="state-missed"]')).not.toBeNull();
  });

  it('reloads on live call events (character answers)', () => {
    const { fixture, http, el, store } = setup([call(2, 'ACCEPTED')]);
    store.callVersion.update((v) => v + 1);
    fixture.detectChanges();
    http.expectOne('/api/calls').flush([
      message({ id: 10, channel: 'CALL', threadRootId: 2, body: 'Es geht um die Rate.', gameTime: 5000 }),
      call(2, 'ACCEPTED'),
    ]);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="call-line"]').length).toBe(2);
    expect(el.querySelectorAll('[data-testid="call-row"]').length).toBe(1);
  });
});
