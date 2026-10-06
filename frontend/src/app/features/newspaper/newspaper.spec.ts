import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { IssueView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { Newspaper } from './newspaper';

const issue = (over: Partial<IssueView> = {}): IssueView => ({
  id: 2, issueNumber: 2, midMonth: false, period: 4, cropYear: 1, fromGameTime: 30 * DAY, publishedGameTime: 60 * DAY,
  headline: 'Maibaum steht', articles: [
    { id: 11, section: 'VILLAGE', sectionTitle: 'Aus dem Dorf', position: 0, headline: 'Maibaum steht', body: 'Das Dorf feiert.',
      fallback: false, pending: false },
    { id: 12, section: 'MARKET', sectionTitle: 'Markt', position: 1, headline: null, body: null, fallback: false, pending: true },
  ], ...over,
});

describe('Newspaper', () => {
  function setup(list: IssueView[]) {
    TestBed.configureTestingModule({ imports: [Newspaper], providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Newspaper);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/newspaper').flush(list);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('opens the newest issue with its sections and keeps older issues readable', () => {
    const older = issue({ id: 1, issueNumber: 1, headline: 'Neue Nachbarn', articles: [
      { id: 5, section: 'FARM', sectionTitle: 'Vom Hof', position: 0, headline: 'Neue Nachbarn', body: 'Text', fallback: true,
        pending: false }] });
    const { el, fixture } = setup([issue(), older]);
    expect(el.querySelectorAll('[data-testid="issue"]').length).toBe(2);
    expect(el.querySelector('[data-testid="issue-view"] h2')?.textContent).toContain('Maibaum steht');
    const articles = el.querySelectorAll('[data-testid="article"]');
    expect(articles.length).toBe(2);
    expect(articles[1].textContent).toContain('noch geschrieben');
    (el.querySelectorAll('[data-testid="issue"]')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="article"]')?.getAttribute('data-section')).toBe('FARM');
  });

  it('says when there is no issue yet', () => {
    const { el } = setup([]);
    expect(el.querySelector('[data-testid="no-issue"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="issue-view"]')).toBeNull();
  });
});
