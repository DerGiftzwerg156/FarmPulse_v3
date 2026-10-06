import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { CaseView, ContractorQuoteView, WorkOptionView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Button } from '../../shared/ui/button';

/**
 * Roadmap V3.1 R31-A1: "Lohnunternehmer beauftragen" for an own field in the Flurkarte. The backend offers only the
 * works that fit the field state (with the price and, for the harvest, the litres for the own silo). Owner decisions
 * 2026-10-06: up to `maxWorks` works are ticked and ordered at once (e.g. cultivate, sow, fertilise); each tick reloads
 * the form so the other works are checked on the field as the ticked ones leave it. All are done at the end of the
 * next game day.
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
        @if (q.openOrders.length) {
          <p class="text-[12px] text-text" data-testid="contractor-open">
            {{ 'farmland.contractor.open' | t: { works: openWorks(q.openOrders), time: (q.openOrders[0].deadlineGameTime | gameTime), price: (openTotal(q.openOrders) | money) } }}
          </p>
        } @else {
          <p class="mb-2 text-[11px] text-muted" data-testid="contractor-intro">{{ 'farmland.contractor.intro' | t: { max: q.maxWorks, time: (q.doneByGameTime | gameTime) } }}</p>
          <ul class="space-y-1.5" data-testid="contractor-options">
            @for (o of q.options; track o.work) {
              <li class="flex flex-wrap items-center gap-2 text-[12px]" data-testid="contractor-option" [attr.data-work]="o.work">
                <label class="flex w-32 items-center gap-2 text-text">
                  <input type="checkbox" [checked]="isSelected(o.work)" [disabled]="busy() || (!!o.reason && !isSelected(o.work))"
                    (change)="toggle(o.work)" data-testid="contractor-select" />
                  {{ o.work | label: 'fieldWork' }}
                </label>
                <span class="w-24 font-mono text-text">{{ o.price | money }}</span>
                @if (o.reason) {
                  <span class="text-muted" data-testid="contractor-reason">{{ o.reason | label: 'contractorReason' }}</span>
                } @else {
                  @if (o.work === 'SOW' && isSelected('SOW')) {
                    <select class="fp-input w-36" [value]="fruitType() ?? q.fruitTypes[0]" (change)="fruitType.set($any($event.target).value)" data-testid="contractor-fruit">
                      @for (fr of q.fruitTypes; track fr) {
                        <option [value]="fr">{{ fr | label: 'fillType' }}</option>
                      }
                    </select>
                  }
                  @if (o.harvestLiters !== null) {
                    <span class="text-muted" data-testid="contractor-liters">{{ 'farmland.contractor.liters' | t: { liters: (o.harvestLiters | num), fillType: (o.fillType | label: 'fillType') } }}</span>
                  }
                }
              </li>
            }
          </ul>
          <div class="mt-3 flex flex-wrap items-center gap-3">
            @if (selected().length) {
              <span class="text-[12px] text-text" data-testid="contractor-total">
                @if (selected().length === 1) {
                  {{ 'farmland.contractor.totalOne' | t: { price: (total() | money) } }}
                } @else {
                  {{ 'farmland.contractor.total' | t: { count: selected().length, price: (total() | money) } }}
                }
              </span>
            }
            <app-button variant="secondary" [disabled]="busy() || !canOrder()" (pressed)="order(q)" data-testid="contractor-order">{{ 'farmland.contractor.order' | t }}</app-button>
          </div>
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
  /** The ticked works (sent with every reload of the form). */
  readonly selected = signal<string[]>([]);
  readonly fruitType = signal<string | null>(null);
  readonly busy = signal(false);
  readonly info = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  private readonly chosen = computed<WorkOptionView[]>(() =>
    (this.quote()?.options ?? []).filter((o) => this.selected().includes(o.work)));
  readonly total = computed(() => this.chosen().reduce((sum, o) => sum + o.price, 0));
  /** Something is ticked and every ticked work fits one after the other. */
  readonly canOrder = computed(() => this.chosen().length > 0 && this.chosen().length === this.selected().length
    && this.chosen().every((o) => !o.reason));

  constructor() {
    effect(() => {
      const id = this.farmlandId();
      untracked(() => {
        this.info.set(null);
        this.error.set(null);
        this.fruitType.set(null);
        this.selected.set([]);
        this.quote.set(null);
        this.load(id);
      });
    });
  }

  /** Reloads the form for the ticked works; the shown form stays until the answer is there. */
  load(id = this.farmlandId()): void {
    this.loadError.set(null);
    this.api.contractorQuote(id, this.selected()).subscribe({
      next: (q) => this.quote.set(q),
      error: (e) => this.loadError.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  isSelected(work: string): boolean {
    return this.selected().includes(work);
  }

  toggle(work: string): void {
    this.selected.update((s) => (s.includes(work) ? s.filter((w) => w !== work) : [...s, work]));
    this.error.set(null);
    this.info.set(null);
    this.load();
  }

  openWorks(orders: CaseView[]): string {
    return orders.map((o) => this.i18n.t(`enums.fieldWork.${o.reference}`)).join(', ');
  }

  openTotal(orders: CaseView[]): number {
    return orders.reduce((sum, o) => sum + (o.offerAmount ?? 0), 0);
  }

  order(q: ContractorQuoteView): void {
    const works = this.selected();
    const fruit = works.includes('SOW') ? (this.fruitType() ?? q.fruitTypes[0] ?? null) : null;
    this.busy.set(true);
    this.info.set(null);
    this.error.set(null);
    this.api.orderContractorWork(q.farmlandId, works, fruit).subscribe({
      next: () => {
        this.busy.set(false);
        this.selected.set([]);
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
