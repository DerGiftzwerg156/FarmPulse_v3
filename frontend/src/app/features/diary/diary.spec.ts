import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { DiaryView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY } from '../../../testing/fixtures';
import { Diary, groupByDay } from './diary';

const entry = (id: number, day: number, over: Partial<DiaryView> = {}): DiaryView => ({
  id, gameTime: day * DAY + id, gameDay: day, entryType: 'AUTO', category: 'CREDIT', title: `Eintrag ${id}`, text: 'Text', ...over,
});
const backstory = entry(1, 0, { category: 'BACKSTORY', title: 'Wie alles begann', text: 'Den Hof hat mir mein Großvater vererbt.' });

describe('groupByDay', () => {
  it('groups chronologically and can reverse', () => {
    const list = [backstory, entry(2, 3), entry(3, 3), entry(4, 5)];
    expect(groupByDay(list, false).map((d) => [d.day, d.entries.length])).toEqual([[0, 1], [3, 2], [5, 1]]);
    expect(groupByDay(list, true)[0].day).toBe(5);
  });
});

describe('Diary', () => {
  function setup(list: DiaryView[]) {
    TestBed.configureTestingModule({
      imports: [Diary],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Diary);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/diary').flush(list);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, http, el, store: TestBed.inject(GameStateStore) };
  }

  it('shows the backstory as the first entry', () => {
    const { el } = setup([backstory, entry(2, 3)]);
    const first = el.querySelector('[data-testid="diary-entry"]');
    expect(first?.textContent).toContain('Wie alles begann');
    expect(first?.textContent).toContain('Vorgeschichte');
    expect(el.querySelectorAll('[data-testid="diary-day"]').length).toBe(2);
  });

  it('filters by category and toggles the order', () => {
    const { el, fixture } = setup([backstory, entry(2, 3), entry(3, 4, { category: 'MARKET' })]);
    const select = el.querySelector('[data-testid="diary-filter"]') as HTMLSelectElement;
    select.value = 'MARKET';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="diary-entry"]').length).toBe(1);
    select.value = '';
    select.dispatchEvent(new Event('change'));
    (el.querySelector('[data-testid="diary-order"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="diary-entry"]')?.textContent).toContain('Eintrag 3');
  });

  it('adds a note that is marked as purely narrative', () => {
    const { el, fixture, http } = setup([backstory]);
    expect(el.querySelector('[data-testid="narrative-hint"]')?.textContent).toContain('keinerlei Auswirkung');
    const title = el.querySelector('[data-testid="note-title"]') as HTMLInputElement;
    title.value = 'Erste Ernte';
    title.dispatchEvent(new Event('input'));
    const text = el.querySelector('[data-testid="note-text"]') as HTMLTextAreaElement;
    text.value = 'Der Weizen stand gut.';
    text.dispatchEvent(new Event('input'));
    (el.querySelector('[data-testid="note-save"] button') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/diary/entries');
    expect(req.request.body).toEqual({ title: 'Erste Ernte', text: 'Der Weizen stand gut.' });
    req.flush(entry(9, 6, { entryType: 'PLAYER_NOTE', category: null, title: 'Erste Ernte', text: 'Der Weizen stand gut.' }));
    fixture.detectChanges();
    const notes = el.querySelectorAll('[data-testid="diary-entry"][data-type="PLAYER_NOTE"]');
    expect(notes.length).toBe(1);
    expect(notes[0].querySelector('[data-testid="note-badge"]')).not.toBeNull();
  });

  it('requires a title', () => {
    const { el, fixture, http } = setup([]);
    (el.querySelector('[data-testid="note-save"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectNone('/api/diary/entries');
    expect(el.querySelector('[data-testid="note-error"]')).not.toBeNull();
  });

  it('reloads on live diary events', () => {
    const { el, fixture, http, store } = setup([backstory]);
    store.diaryVersion.update((v) => v + 1);
    fixture.detectChanges();
    http.expectOne('/api/diary').flush([backstory, entry(2, 1)]);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="diary-entry"]').length).toBe(2);
  });
});
