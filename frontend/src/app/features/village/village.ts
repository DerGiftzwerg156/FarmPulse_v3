import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CharacterDetailView, CharacterView, FarmlandShort, ReputationView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';
import { TrustMeter } from './trust-meter';

export const CATEGORY_ORDER = ['MANDATORY', 'SUBSTITUTE', 'DYNAMIC', 'EMPLOYEE'];

/**
 * Characters & village (AP-8.8): character list with abstract trust, detail with personality, proactive messages
 * with a visible pacing hint (never blocking), village reputation as tier only, direct negotiation for land owners.
 */
@Component({
  selector: 'app-village',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Card, Badge, Button, TrustMeter, PageErrorView],
  templateUrl: './village.html',
})
export class Village {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly router = inject(Router);
  private readonly i18n = inject(TranslationService);

  /** `?character=` opens a character (links from calls, applicants, fields). */
  readonly character = input<string>();

  readonly characters = signal<CharacterView[] | null>(null);
  readonly reputation = signal<ReputationView | null>(null);
  readonly detail = signal<CharacterDetailView | null>(null);
  readonly selected = signal<number | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly showFormer = signal(false);
  readonly text = signal('');
  readonly channel = signal<'MAIL' | 'CALL'>('MAIL');
  readonly sending = signal(false);
  readonly info = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);

  readonly groups = computed(() => {
    const list = (this.characters() ?? []).filter((c) => this.showFormer() || c.status !== 'TERMINATED');
    return CATEGORY_ORDER.map((cat) => ({ category: cat, items: list.filter((c) => c.category === cat) }))
      .concat([{ category: 'OTHER', items: list.filter((c) => !CATEGORY_ORDER.includes(c.category)) }])
      .filter((g) => g.items.length > 0);
  });
  readonly formerCount = computed(() => (this.characters() ?? []).filter((c) => c.status === 'TERMINATED').length);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      this.store.callVersion();
      untracked(() => this.load());
    });
    effect(() => {
      const id = Number(this.character());
      if (id) untracked(() => this.select(id));
    });
  }

  load(): void {
    forkJoin({ characters: this.api.characters(), reputation: this.api.reputation() }).subscribe({
      next: ({ characters, reputation }) => {
        this.characters.set(characters);
        this.reputation.set(reputation);
        this.error.set(null);
        // Refresh an already open detail (live events); a pending first load is not duplicated.
        const id = this.selected();
        if (id !== null && this.detail() !== null) this.loadDetail(id);
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  select(id: number): void {
    if (this.selected() !== id) {
      this.selected.set(id);
      this.detail.set(null);
      this.text.set('');
      this.info.set(null);
      this.actionError.set(null);
    }
    this.loadDetail(id);
  }

  private loadDetail(id: number): void {
    this.api.character(id).subscribe({
      next: (d) => {
        if (this.selected() === id) this.detail.set(d);
      },
      error: (e) => this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  traits(d: CharacterDetailView): string[] {
    return (d.traits ?? '')
      .split(/[,;\n]/)
      .map((t) => t.trim())
      .filter(Boolean);
  }

  send(d: CharacterDetailView): void {
    const text = this.text().trim();
    if (!text) return;
    this.sending.set(true);
    this.actionError.set(null);
    this.api.sendMessage(d.id, text, this.channel()).subscribe({
      next: (r) => {
        this.sending.set(false);
        this.text.set('');
        this.info.set(this.i18n.t(r.pacingActive ? 'village.sentPaced' : 'village.sent', { name: d.name }));
        this.detail.update((x) => (x ? { ...x, pacingActive: true, recentMessages: [r.message, ...x.recentMessages] } : x));
      },
      error: (e) => {
        this.sending.set(false);
        this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  negotiate(d: CharacterDetailView, f: FarmlandShort): void {
    this.actionError.set(null);
    this.api.startDirectNegotiation(d.id, f.farmlandId).subscribe({
      next: (n) => void this.router.navigate(['/farmland'], { queryParams: { negotiation: n.id } }),
      error: (e) => this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }
}
