import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { IssueView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe } from '../../shared/format/format.pipes';
import { Card } from '../../shared/ui/card';

/**
 * Hof-Tablet app "Dorfblatt" (Roadmap V3.1 R31-D1): one issue per FS25 period with the public facts of the village -
 * sections "Aus dem Dorf", "Vom Hof", "Markt", "Amtliches", "Kleinanzeigen" (empty ones are left out). The newest issue
 * is open; older issues stay readable.
 */
@Component({
  selector: 'app-newspaper',
  imports: [TranslatePipe, GameTimePipe, Card],
  template: `
    <section class="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,3fr)]">
      <app-card [title]="'newspaper.issues' | t" data-testid="issues">
        <ul class="space-y-1">
          @for (i of issues(); track i.id) {
            <li>
              <button type="button" class="w-full rounded-lg px-2 py-1.5 text-left text-[12px] hover:bg-bg"
                [class.bg-bg]="selected()?.id === i.id" (click)="select(i)" data-testid="issue" [attr.data-issue]="i.issueNumber">
                <span class="font-display font-bold text-text">{{ 'newspaper.number' | t: { n: i.issueNumber } }}</span>
                <span class="fp-label">@if (i.period !== null) { · {{ ('enums.period.' + i.period) | t }} }@if (i.midMonth) { · {{ 'newspaper.midMonth' | t }} }</span>
                @if (i.headline) { <span class="block truncate text-muted">{{ i.headline }}</span> }
              </button>
            </li>
          } @empty {
            <li class="text-[12px] text-muted" data-testid="no-issue">{{ 'newspaper.none' | t }}</li>
          }
        </ul>
      </app-card>
      @if (selected(); as i) {
        <article class="rounded-[18px] border border-border bg-surface px-5 py-4" data-testid="issue-view">
          <header class="border-b border-border pb-3">
            <div class="fp-label">{{ 'newspaper.masthead' | t: { n: i.issueNumber, time: (i.publishedGameTime | gameTime) } }}</div>
            <h2 class="mt-1 font-display text-[20px] font-bold text-text">{{ i.headline ?? ('newspaper.writing' | t) }}</h2>
          </header>
          @for (a of i.articles; track a.id) {
            <section class="border-b border-border/70 py-3 last:border-b-0" data-testid="article" [attr.data-section]="a.section">
              <div class="fp-label">{{ a.sectionTitle }}</div>
              @if (a.pending) {
                <p class="mt-1 text-[12px] text-muted">{{ 'newspaper.writing' | t }}</p>
              } @else {
                <h3 class="mt-1 font-display text-[15px] font-bold text-text">{{ a.headline }}</h3>
                <p class="mt-1 whitespace-pre-line text-[13px] text-text">{{ a.body }}</p>
              }
            </section>
          }
        </article>
      }
      @if (error(); as e) { <p class="text-[12px] text-danger" data-testid="newspaper-error">{{ e }}</p> }
    </section>
  `,
})
export class Newspaper {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);

  readonly issues = signal<IssueView[]>([]);
  private readonly selectedId = signal<number | null>(null);
  readonly error = signal<string | null>(null);

  /** The chosen issue, else the newest. */
  readonly selected = computed(() => {
    const list = this.issues();
    return list.find((i) => i.id === this.selectedId()) ?? list[0] ?? null;
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.newspaper().subscribe({
      next: (list) => this.issues.set(list),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  select(i: IssueView): void {
    this.selectedId.set(i.id);
  }
}
