import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { ForwardContractsView, ForwardQuoteView, PriceView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

const STATUS_BADGE: Record<string, BadgeVariant> = { OPEN: 'neutral', FULFILLED: 'positive', SHORTFALL: 'negative' };

/**
 * Roadmap V3 R3-M2: forward contracts - fill type, sell point, quantity and delivery month; the backend names the fixed
 * price (preview), the player concludes it by button. Delivered quantity and penalty after the delivery month.
 */
@Component({
  selector: 'app-forward-contract-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Badge, Button, Card],
  template: `
    <app-card [title]="'market.forward.title' | t" data-testid="forward-contracts">
      @if (data(); as d) {
        <form class="flex flex-wrap items-end gap-2" (submit)="$event.preventDefault(); preview()" data-testid="forward-form">
          <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'market.alarm.fillType' | t }}
            <select class="fp-input w-36" [value]="fillType()" (change)="fillType.set($any($event.target).value); quote.set(null)" data-testid="forward-filltype">
              @for (f of fillTypes(); track f) { <option [value]="f">{{ f | label: 'fillType' }}</option> }
            </select>
          </label>
          <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'market.alarm.sellPoint' | t }}
            <select class="fp-input w-40" [value]="sellPoint()" (change)="sellPoint.set($any($event.target).value); quote.set(null)" data-testid="forward-sellpoint">
              @for (s of sellPoints(); track s.id) { <option [value]="s.id">{{ s.name }} ({{ s.price | money }})</option> }
            </select>
          </label>
          <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'market.forward.quantity' | t }}
            <input class="fp-input w-28 font-mono" type="number" [min]="d.minQuantity" [max]="d.maxQuantity" [step]="d.quantityStep"
              [value]="quantity()" (input)="quantity.set(+$any($event.target).value); quote.set(null)" data-testid="forward-quantity" />
          </label>
          <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'market.forward.lead' | t }}
            <select class="fp-input w-32" [value]="lead()" (change)="lead.set(+$any($event.target).value); quote.set(null)" data-testid="forward-lead">
              @for (m of leads(); track m) { <option [value]="m">{{ 'market.forward.months' | t: { n: m } }}</option> }
            </select>
          </label>
          <app-button type="submit" variant="secondary" [disabled]="busy() || !sellPoint()" data-testid="forward-preview">{{ 'market.forward.preview' | t }}</app-button>
        </form>
        @if (quote(); as q) {
          <div class="mt-2 rounded-md border border-accent/40 p-2 text-[12px]" data-testid="forward-quote">
            <p class="text-text">{{ 'market.forward.quote' | t: { price: (q.fixedPrice | money), base: (q.basePrice | money), income: (q.expectedIncome | money), month: monthName(q.deliveryPeriod), quantity: (q.quantity | num) } }}</p>
            <div class="mt-2"><app-button [disabled]="busy()" (pressed)="conclude()" data-testid="forward-conclude">{{ 'market.forward.conclude' | t }}</app-button></div>
          </div>
        }
        @if (error()) {
          <p class="mt-2 text-[12px] text-danger" data-testid="forward-error">{{ error() }}</p>
        }
        <ul class="mt-3 space-y-1.5">
          @for (c of d.contracts; track c.id) {
            <li class="rounded-md border border-border bg-bg px-2.5 py-1.5 text-[12px]" data-testid="forward-contract">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <span class="text-text">{{ c.quantity | num }} l {{ c.fillType | label: 'fillType' }} · {{ sellPointName(c.sellPoint) }} · {{ c.fixedPrice | money }}</span>
                <app-badge [variant]="badge(c.status)">{{ ('market.forward.status.' + c.status) | t }}</app-badge>
              </div>
              <div class="mt-0.5 text-[11px] text-muted">
                @if (c.status === 'OPEN') {
                  {{ 'market.forward.window' | t: { from: (c.deliveryStartGameTime | gameTime), to: (c.deadlineGameTime | gameTime) } }}
                } @else {
                  {{ 'market.forward.result' | t: { delivered: (c.deliveredQuantity | num), quantity: (c.quantity | num) } }}
                  @if (c.penalty) { · {{ 'market.forward.penalty' | t: { amount: (c.penalty | money) } }} }
                }
              </div>
            </li>
          } @empty {
            <li class="text-[12px] text-muted">{{ 'market.forward.none' | t }}</li>
          }
        </ul>
        <p class="mt-2 text-[11px] text-muted">{{ 'market.forward.hint' | t: { factor: (d.factorPerMonthPercent | num: 1), penalty: (d.penaltySharePercent | num: 0), max: d.maxOpen } }}</p>
      } @else {
        <p class="text-sm text-muted">{{ 'common.loading' | t }}</p>
      }
    </app-card>
  `,
})
export class ForwardContractCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly prices = input<PriceView[]>([]);

  readonly data = signal<ForwardContractsView | null>(null);
  readonly fillType = signal('');
  readonly sellPoint = signal('');
  readonly quantity = signal(10000);
  readonly lead = signal(1);
  readonly quote = signal<ForwardQuoteView | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly fillTypes = computed(() => [...new Set(this.prices().map((p) => p.fillType))].sort());
  readonly sellPoints = computed(() => this.prices().filter((p) => p.fillType === this.fillType())
    .map((p) => ({ id: p.sellPoint, name: p.sellPointName, price: p.currentPrice })));
  readonly leads = computed(() => {
    const d = this.data();
    if (!d) return [];
    return Array.from({ length: d.maxLeadMonths - d.minLeadMonths + 1 }, (_, i) => d.minLeadMonths + i);
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      untracked(() => this.load());
    });
    effect(() => {
      const types = this.fillTypes();
      if (!this.fillType() && types.length) untracked(() => this.fillType.set(types[0]));
    });
    effect(() => {
      const points = this.sellPoints();
      if (!points.some((p) => p.id === this.sellPoint())) untracked(() => this.sellPoint.set(points[0]?.id ?? ''));
    });
  }

  load(): void {
    this.api.forwardContracts().subscribe({ next: (d) => this.data.set(d), error: () => this.data.set(null) });
  }

  badge(status: string): BadgeVariant {
    return STATUS_BADGE[status] ?? 'neutral';
  }

  sellPointName(id: string): string {
    return this.prices().find((p) => p.sellPoint === id)?.sellPointName ?? id;
  }

  monthName(period: number | null): string {
    if (period === null) return '–';
    const key = `enums.period.${period}`;
    return this.i18n.has(key) ? this.i18n.t(key) : String(period);
  }

  preview(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.forwardQuote(this.fillType(), this.sellPoint(), this.quantity(), this.lead()).subscribe({
      next: (q) => {
        this.busy.set(false);
        this.quote.set(q);
      },
      error: (e) => this.fail(e),
    });
  }

  conclude(): void {
    const q = this.quote();
    if (!q) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.concludeForward(q.fillType, q.sellPoint, q.quantity, q.leadMonths).subscribe({
      next: () => {
        this.busy.set(false);
        this.quote.set(null);
        this.load();
      },
      error: (e) => this.fail(e),
    });
  }

  private fail(e: unknown): void {
    this.busy.set(false);
    this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
  }
}
