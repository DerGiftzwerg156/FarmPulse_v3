import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { ContractorQuoteView, WorkOptionView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Button } from '../../shared/ui/button';

/**
 * Roadmap V3.1 R31-A1: "Lohnunternehmer beauftragen" for an own field in the Flurkarte. The backend offers only the
 * works that fit the field state (with the price and, for the harvest, the litres for the own silo); the contractor
 * comes on a game day in the shown range.
 */
@Component({
  selector: 'app-contractor-work-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Button],
  template: `
    <div class="mt-3 border-t border-border pt-3" data-testid="contractor-work">
      <div class="fp-label mb-2">{{ 'farmland.contractor.title' | t }}</div>
      @if (loadError(); as e) {
        <p class="text-[12px] text-muted" data-testid="contractor-unavailable">{{ e }}</p>
      } @else if (quote(); as q) {
        @if (q.openOrder; as o) {
          <p class="text-[12px] text-text" data-testid="contractor-open">
            {{ 'farmland.contractor.open' | t: { work: (o.reference | label: 'fieldWork'), time: (o.deadlineGameTime | gameTime), price: (o.offerAmount | money) } }}
          </p>
        } @else {
          <p class="mb-2 text-[11px] text-muted">{{ 'farmland.contractor.intro' | t: { min: q.daysMin, max: q.daysMax } }}</p>
          <ul class="space-y-1.5" data-testid="contractor-options">
            @for (o of q.options; track o.work) {
              <li class="flex flex-wrap items-center gap-2 text-[12px]" data-testid="contractor-option" [attr.data-work]="o.work">
                <span class="w-28 text-text">{{ o.work | label: 'fieldWork' }}</span>
                <span class="w-24 font-mono text-text">{{ o.price | money }}</span>
                @if (o.reason) {
                  <span class="text-muted" data-testid="contractor-reason">{{ o.reason | label: 'contractorReason' }}</span>
                } @else {
                  @if (o.work === 'SOW') {
                    <select class="fp-input w-36" [value]="fruitType() ?? q.fruitTypes[0]" (change)="fruitType.set($any($event.target).value)" data-testid="contractor-fruit">
                      @for (fr of q.fruitTypes; track fr) {
                        <option [value]="fr">{{ fr | label: 'fillType' }}</option>
                      }
                    </select>
                  }
                  @if (o.harvestLiters !== null) {
                    <span class="text-muted" data-testid="contractor-liters">{{ 'farmland.contractor.liters' | t: { liters: (o.harvestLiters | num), fillType: (o.fillType | label: 'fillType') } }}</span>
                  }
                  <app-button variant="secondary" [disabled]="busy()" (pressed)="order(q, o)" data-testid="contractor-order">{{ 'farmland.contractor.order' | t }}</app-button>
                }
              </li>
            }
          </ul>
        }
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
      @if (info(); as i) { <p class="mt-2 text-[12px] text-accent" data-testid="contractor-info">{{ i }}</p> }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="contractor-error">{{ e }}</p> }
    </div>
  `,
})
export class ContractorWorkCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly farmlandId = input.required<number>();
  /** Emitted after an order so the page can reload its case list. */
  readonly ordered = output<void>();

  readonly quote = signal<ContractorQuoteView | null>(null);
  readonly loadError = signal<string | null>(null);
  readonly fruitType = signal<string | null>(null);
  readonly busy = signal(false);
  readonly info = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.farmlandId();
      untracked(() => {
        this.info.set(null);
        this.error.set(null);
        this.fruitType.set(null);
        this.load(id);
      });
    });
  }

  load(id = this.farmlandId()): void {
    this.quote.set(null);
    this.loadError.set(null);
    this.api.contractorQuote(id).subscribe({
      next: (q) => this.quote.set(q),
      error: (e) => this.loadError.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  order(q: ContractorQuoteView, o: WorkOptionView): void {
    const fruit = o.work === 'SOW' ? (this.fruitType() ?? q.fruitTypes[0] ?? null) : null;
    this.busy.set(true);
    this.info.set(null);
    this.error.set(null);
    this.api.orderContractorWork(q.farmlandId, o.work, fruit).subscribe({
      next: () => {
        this.busy.set(false);
        this.info.set(this.i18n.t('farmland.contractor.ordered'));
        this.load(q.farmlandId);
        this.ordered.emit();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
