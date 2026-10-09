import { Component, effect, inject, signal, untracked } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { CooperativeView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3.1 R31-D7: shares of the cooperative in the app "Handel" - buy (form, fixed denomination), cancel with the
 * notice period (repaid at the nominal value), the expected dividend rate and the board seat. The general assembly
 * and the board meetings run as cases in "Kalender".
 */
@Component({
  selector: 'app-coop-shares-card',
  imports: [TranslatePipe, MoneyPipe, GameTimePipe, Badge, Button, Card],
  template: `
    <app-card [title]="'trade.coop.title' | t" data-testid="coop-shares">
      <div card-actions>
        @if (view()?.board) { <app-badge variant="positive" data-testid="coop-board">{{ 'trade.coop.board' | t }}</app-badge> }
      </div>
      @if (view(); as v) {
        <p class="mb-2 text-[12px] text-muted">{{ 'trade.coop.intro' | t: { price: (v.sharePrice | money), max: v.maxShares, months: v.noticeMonths } }}</p>
        <div class="text-[13px] text-text" data-testid="coop-holding">
          {{ 'trade.coop.holding' | t: { shares: v.shares, value: ((v.shares * v.sharePrice) | money) } }}
          @if (v.noticedShares > 0) { · {{ 'trade.coop.noticed' | t: { shares: v.noticedShares } }} }
        </div>
        <div class="mt-1 text-[12px] text-muted" data-testid="coop-dividend">{{ 'trade.coop.dividend' | t: { rate: v.dividendRatePercent } }}</div>
        @if (!v.board && v.shares < v.boardMinShares) {
          <div class="mt-1 text-[11px] text-muted">{{ 'trade.coop.boardHint' | t: { shares: v.boardMinShares } }}</div>
        }
        @if (v.enabled) {
          <div class="mt-3 flex flex-wrap items-end gap-2">
            <label class="space-y-1">
              <span class="fp-label">{{ 'trade.coop.count' | t }}</span>
              <input class="fp-input w-24 font-mono" type="number" min="1" step="1" [value]="count() ?? ''"
                (input)="setCount($any($event.target).valueAsNumber)" data-testid="coop-count" />
            </label>
            <app-button [disabled]="busy() || !count()" (pressed)="buy()" data-testid="coop-buy">{{ 'trade.coop.buy' | t: { amount: (((count() ?? 0) * v.sharePrice) | money) } }}</app-button>
            <app-button variant="secondary" [disabled]="busy() || !count()" (pressed)="cancel()" data-testid="coop-cancel">{{ 'trade.coop.cancel' | t }}</app-button>
          </div>
        }
        <ul class="mt-3 space-y-1 text-[12px]">
          @for (n of v.notices; track n.id) {
            <li data-testid="coop-notice">{{ (n.paidGameTime === null ? 'trade.coop.noticeOpen' : 'trade.coop.noticePaid') | t: { shares: n.shares, time: ((n.paidGameTime ?? n.dueGameTime) | gameTime) } }}</li>
          }
        </ul>
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="coop-error">{{ e }}</p> }
    </app-card>
  `,
})
export class CoopSharesCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);

  readonly view = signal<CooperativeView | null>(null);
  /** Number of shares (form field; numbers only via inputs). */
  readonly count = signal<number | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.cooperative().subscribe({
      next: (v) => this.view.set(v),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  setCount(value: number): void {
    this.count.set(Number.isFinite(value) && value >= 1 ? Math.floor(value) : null);
  }

  buy(): void {
    const n = this.count();
    if (n) this.run(this.api.buyShares(n));
  }

  cancel(): void {
    const n = this.count();
    if (n) this.run(this.api.cancelShares(n));
  }

  private run(o: Observable<CooperativeView>): void {
    this.busy.set(true);
    this.error.set(null);
    o.subscribe({
      next: (v) => {
        this.busy.set(false);
        this.view.set(v);
        this.count.set(null);
        this.store.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
