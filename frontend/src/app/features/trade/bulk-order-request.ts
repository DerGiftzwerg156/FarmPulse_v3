import { Component, inject, input, output, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { BulkOrderMonthView, CaseView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';

/**
 * Roadmap V3.2 R32-G: request of a bulk buyer inside the case card ("Handel → Großaufträge" and "Aufgaben").
 * "Sofort liefern" (G2, whole amount from the own silos) and "Ablehnen" go through the case actions; "Termin
 * vereinbaren" (G3) lists the delivery months with the fixed price the backend names, busy months cannot be chosen.
 * Case fields: reference = fill type, title = sell point, quantity = litres, costAmount / offerAmount = instant price.
 */
@Component({
  selector: 'app-bulk-order-request',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, Badge, Button],
  template: `
    <div class="mt-1 text-[12px] text-text" data-testid="bulk-order-request">{{ 'trade.bulk.requestText' | t: { name: c().character?.name ?? '–', sellPoint: c().title ?? '–', quantity: (c().quantity | num), fillType: (c().reference | label: 'fillType'), amount: (c().offerAmount | money), unit: (c().costAmount | money) } }}</div>
    @if (c().status === 'AWAITING_PLAYER') {
      <div class="mt-2 flex flex-wrap gap-2">
        <app-button [disabled]="busy()" (pressed)="deliver()" data-testid="bulk-deliver">{{ 'trade.bulk.deliver' | t }}</app-button>
        <app-button variant="secondary" [disabled]="busy()" (pressed)="toggleMonths()" data-testid="bulk-term">{{ 'trade.bulk.term' | t }}</app-button>
        <app-button variant="secondary" [disabled]="busy()" (pressed)="decline()" data-testid="bulk-decline">{{ 'contracts.decline' | t }}</app-button>
      </div>
      @if (months(); as ms) {
        <div class="mt-2 rounded-md border border-accent/40 p-2 text-[12px]" data-testid="bulk-months">
          <label class="flex flex-col gap-1 text-muted">{{ 'trade.bulk.month' | t }}
            <select class="fp-input" [value]="lead() ?? ''" (change)="lead.set(+$any($event.target).value || null)" data-testid="bulk-month">
              <option value="">{{ 'trade.bulk.chooseMonth' | t }}</option>
              @for (m of ms; track m.leadMonths) {
                <option [value]="m.leadMonths" [disabled]="!m.available">{{ monthLine(m) }}</option>
              }
            </select>
          </label>
          @if (chosen(); as m) {
            <p class="mt-2 text-text" data-testid="bulk-quote">{{ 'trade.bulk.quote' | t: { price: (m.fixedPrice | money), income: (m.expectedIncome | money), month: monthName(m.period), quantity: (c().quantity | num), sellPoint: c().title ?? '–' } }}</p>
            <div class="mt-2"><app-button [disabled]="busy()" (pressed)="agree(m.leadMonths)" data-testid="bulk-agree">{{ 'trade.bulk.agree' | t }}</app-button></div>
          }
          <p class="mt-1 text-[11px] text-muted">{{ 'trade.bulk.termHint' | t }}</p>
        </div>
      }
    } @else if (c().status === 'IN_PROGRESS') {
      <app-badge variant="positive">{{ 'trade.inTransfer' | t }}</app-badge>
    } @else if (c().resolution === 'TERM_AGREED') {
      <app-badge variant="positive" data-testid="bulk-agreed">{{ 'trade.bulk.agreed' | t }}</app-badge>
    }
    <p class="mt-1 text-[11px] text-muted">{{ 'trade.bulk.requestHint' | t }}</p>
    @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="bulk-error">{{ e }}</p> }
  `,
})
export class BulkOrderRequest {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly c = input.required<CaseView>();
  readonly changed = output<unknown>();

  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  /** Delivery months of "Termin vereinbaren" (null = closed). */
  readonly months = signal<BulkOrderMonthView[] | null>(null);
  /** Chosen lead in months (form field). */
  readonly lead = signal<number | null>(null);

  chosen(): BulkOrderMonthView | null {
    return this.months()?.find((m) => m.leadMonths === this.lead() && m.available) ?? null;
  }

  monthName(period: number | null): string {
    if (period === null) return '–';
    const key = `enums.period.${period}`;
    return this.i18n.has(key) ? this.i18n.t(key) : String(period);
  }

  monthLine(m: BulkOrderMonthView): string {
    const line = this.i18n.t('trade.bulk.monthOption', { n: m.leadMonths, month: this.monthName(m.period) });
    return m.available ? line : `${line} – ${this.i18n.t('trade.bulk.reason.' + m.reason)}`;
  }

  toggleMonths(): void {
    if (this.months()) {
      this.months.set(null);
      this.lead.set(null);
      return;
    }
    this.error.set(null);
    this.api.bulkOrderMonths(this.c().id).subscribe({
      next: (ms) => this.months.set(ms),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  deliver(): void {
    this.run(this.api.caseAction(this.c().id, 'accept'));
  }

  decline(): void {
    this.run(this.api.caseAction(this.c().id, 'decline'));
  }

  agree(leadMonths: number): void {
    this.run(this.api.agreeBulkOrder(this.c().id, leadMonths));
  }

  private run(o: Observable<unknown>): void {
    this.busy.set(true);
    this.error.set(null);
    o.subscribe({
      next: (v) => {
        this.busy.set(false);
        this.months.set(null);
        this.lead.set(null);
        this.changed.emit(v);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
