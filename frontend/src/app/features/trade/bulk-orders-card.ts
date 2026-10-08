import { Component, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { BulkOrdersView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Card } from '../../shared/ui/card';

const STATUS_BADGE: Record<string, BadgeVariant> = { OPEN: 'neutral', FULFILLED: 'positive', SHORTFALL: 'negative' };

/**
 * Roadmap V3.2 R32-G3 / G4: the bulk orders with a delivery month in "Handel → Großaufträge" - sell point, amount,
 * fixed price and delivery month; delivered amount and penalty after the month. The requests themselves are cases.
 */
@Component({
  selector: 'app-bulk-orders-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Badge, Card],
  template: `
    <app-card [title]="'trade.bulk.ordersTitle' | t" data-testid="bulk-orders">
      @if (view(); as v) {
        <p class="mb-2 text-[12px] text-muted">{{ 'trade.bulk.intro' | t: { markup: v.instantMarkupPercent, penalty: v.penaltySharePercent, max: v.maxOpen, from: v.minLeadMonths, to: v.maxLeadMonths } }}</p>
        @if (!v.silosTracked) {
          <p class="mb-2 text-[12px] text-warn" data-testid="bulk-no-silos">{{ 'trade.bulk.noSilos' | t }}</p>
        }
        <ul class="space-y-1.5">
          @for (o of v.orders; track o.id) {
            <li class="rounded-md border border-border bg-bg px-2.5 py-1.5 text-[12px]" data-testid="bulk-order">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <span class="text-text">{{ 'trade.bulk.orderLine' | t: { quantity: (o.quantity | num), fillType: (o.fillType | label: 'fillType'), sellPoint: o.sellPointName, price: (o.fixedPrice | money), month: monthName(o.deliveryPeriod) } }}</span>
                <app-badge [variant]="badge(o.status)">{{ 'trade.bulk.status.' + o.status | t }}</app-badge>
              </div>
              @if (o.status === 'OPEN') {
                <div class="mt-0.5 text-muted">{{ 'trade.bulk.window' | t: { from: (o.deliveryStartGameTime | gameTime), to: (o.deadlineGameTime | gameTime), income: (o.expectedIncome | money) } }}</div>
              } @else {
                <div class="mt-0.5 text-muted" data-testid="bulk-result">{{ 'trade.bulk.result' | t: { delivered: (o.deliveredQuantity ?? 0 | num), quantity: (o.quantity | num) } }}@if (o.penalty) { · {{ 'trade.bulk.penalty' | t: { amount: (o.penalty | money) } }} }</div>
              }
            </li>
          } @empty {
            <li class="text-[12px] text-muted">{{ 'trade.bulk.none' | t }}</li>
          }
        </ul>
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="bulk-orders-error">{{ e }}</p> }
    </app-card>
  `,
})
export class BulkOrdersCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);

  readonly view = signal<BulkOrdersView | null>(null);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.bulkOrders().subscribe({
      next: (v) => {
        this.view.set(v);
        this.error.set(null);
      },
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  badge(status: string): BadgeVariant {
    return STATUS_BADGE[status] ?? 'neutral';
  }

  monthName(period: number | null): string {
    if (period === null) return '–';
    const key = `enums.period.${period}`;
    return this.i18n.has(key) ? this.i18n.t(key) : String(period);
  }
}
