import { Component, effect, inject, signal, untracked } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { TaxOverviewView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { Stat } from '../../shared/ui/stat';

/**
 * Roadmap V2 R2-E1: tax overview on the bank page - estimated tax of the running FS25 year, next prepayment, the
 * traceable calculation of the last assessment and the request for a tax advisor. Bills are paid under
 * "Verträge & Vorgänge".
 */
@Component({
  selector: 'app-tax-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, Card, Stat, Badge, Button],
  templateUrl: './tax-card.html',
  host: { class: 'block' },
})
export class TaxCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly overview = signal<TaxOverviewView | null>(null);
  readonly failed = signal(false);
  readonly busy = signal(false);
  readonly actionError = signal<string | null>(null);
  readonly advisorRequested = signal(false);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.api.tax().subscribe({
      next: (o) => {
        this.overview.set(o);
        this.failed.set(false);
      },
      error: () => this.failed.set(true),
    });
  }

  requestAdvisor(): void {
    this.busy.set(true);
    this.actionError.set(null);
    this.api.requestTaxAdvisorOffer().subscribe({
      next: () => {
        this.busy.set(false);
        this.advisorRequested.set(true);
      },
      error: (e) => {
        this.busy.set(false);
        this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
