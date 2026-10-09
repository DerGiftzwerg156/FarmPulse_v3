import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { ChatGroupView, ChatMessageView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, character, savegame } from '../../../testing/fixtures';
import { Chat } from './chat';

const msg = (over: Partial<ChatMessageView> = {}): ChatMessageView => ({
  id: 1, groupId: 1, character: character({ id: 4, name: 'Hauke Harms' }), kind: 'HELP_REQUEST', topic: 'GOODS_REQUEST',
  text: 'Wer hat Weizen übrig?', tone: null, link: '/handel?case=9', gameTime: 10 * DAY, pending: false, ...over,
});
const groups: ChatGroupView[] = [
  { id: 1, key: 'DORF', name: 'Dorf', members: [character({ id: 4, name: 'Hauke Harms' })], last: null },
  { id: 2, key: 'NACHBARN', name: 'Nachbarn', members: [], last: null },
];

describe('Chat', () => {
  function setup(messages: ChatMessageView[]) {
    TestBed.configureTestingModule({
      imports: [Chat],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Chat);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/chat/groups').flush(groups);
    http.expectOne('/api/chat/groups/1/messages').flush(messages);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the messages of the first group and opens the link of a help request', () => {
    const { el } = setup([msg(), msg({ id: 2, character: null, kind: 'PLAYER', text: 'Ich schau mal', link: null }),
      msg({ id: 3, kind: 'REPLY', text: null, link: null, pending: true })]);
    const items = el.querySelectorAll('[data-testid="chat-message"]');
    expect(items.length).toBe(3);
    expect(items[2].textContent).toContain('schreibt');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    (el.querySelector('[data-testid="chat-link"]') as HTMLButtonElement).click();
    expect(navigate).toHaveBeenCalledWith('/handel?case=9');
  });

  it('posts a message and shows the pacing note', () => {
    const { el, fixture, http } = setup([]);
    const input = el.querySelector('[data-testid="chat-input"]') as HTMLTextAreaElement;
    input.value = 'Danke euch!';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('[data-testid="chat-send"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/chat/groups/1/messages');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ text: 'Danke euch!' });
    req.flush({ message: msg({ character: null, kind: 'PLAYER' }), pacingActive: true });
    http.expectOne('/api/chat/groups/1/messages').flush([]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="chat-pacing"]')).not.toBeNull();
  });
});
