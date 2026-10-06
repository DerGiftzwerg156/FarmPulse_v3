import { Component, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { FarmHolidayView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3.1 R31-D6: "Ferien auf dem Hof" in the app "Handel". A one-off setup by button, then the guests pay at
 * every month start; the card shows the factors of the running month and the past months with the complaints.
 */
@Component({
  selector: 'app-farm-holiday-card',
  imports: [TranslatePipe, MoneyPipe, Badge, Button, Card],
  template: `
    <app-card [title]="'trade.holiday.title' | t" data-testid="farm-holiday">
      @if (view(); as v) {
        @if (v.since === null) {
          <p class="mb-3 text-[12px] text-muted">{{ 'trade.holiday.intro' | t: { cost: (v.setupCost | money), base: (v.baseIncomePerMonth | money) } }}</p>
          @if (v.enabled) {
            <app-button [disabled]="busy()" (pressed)="setup()" data-testid="holiday-setup">{{ 'trade.holiday.setup' | t: { cost: (v.setupCost | money) } }}</app-button>
          }
        } @else {
          <div class="text-[12px] text-text" data-testid="holiday-preview">
            {{ 'trade.holiday.preview' | t: { month: (('enums.period.' + v.preview.period) | t), amount: (v.preview.income | money) } }}
          </div>
          <div class="mt-1 text-[11px] text-muted">
            {{ 'trade.holiday.factors' | t: { season: v.preview.seasonFactor, reputation: v.preview.reputationFactor, animals: v.preview.animalFactor } }}
          </div>
          <ul class="mt-3 space-y-1 text-[12px]">
            @for (m of v.months; track m.monthIndex) {
              <li class="flex flex-wrap items-center justify-between gap-2" data-testid="holiday-month">
                <span>{{ ('enums.period.' + m.period) | t }} · {{ m.income | money }}</span>
                <span class="flex gap-1">
                  @if (m.noise) { <app-badge variant="warning">{{ 'trade.holiday.noise' | t }}</app-badge> }
                  @if (m.smell) { <app-badge variant="warning">{{ 'trade.holiday.smell' | t }}</app-badge> }
                  @if (m.badReview) { <app-badge variant="negative">{{ 'trade.holiday.badReview' | t }}</app-badge> }
                </span>
              </li>
            } @empty {
              <li class="text-muted">{{ 'trade.holiday.firstMonth' | t }}</li>
            }
          </ul>
        }
        <p class="mt-2 text-[11px] text-muted">{{ 'trade.holiday.hint' | t }}</p>
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="holiday-error">{{ e }}</p> }
    </app-card>
  `,
})
export class FarmHolidayCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);

  readonly view = signal<FarmHolidayView | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.farmHoliday().subscribe({
      next: (v) => this.view.set(v),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  setup(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.setupFarmHoliday().subscribe({
      next: (v) => {
        this.busy.set(false);
        this.view.set(v);
        this.store.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
