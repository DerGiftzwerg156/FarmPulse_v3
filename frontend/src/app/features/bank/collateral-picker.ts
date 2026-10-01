import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { CollateralOverviewView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';

/**
 * Roadmap V3 R3-K1: own fields as collateral in the credit form - each with its collateral value (price x loan-to-value).
 * Shows the share of the farm assets above which the bank wants a Grundschuld and the discount at full coverage.
 */
@Component({
  selector: 'app-collateral-picker',
  imports: [TranslatePipe, MoneyPipe, NumberPipe],
  template: `
    @if (overview(); as o) {
      <fieldset class="space-y-1.5" data-testid="collateral-picker">
        <legend class="fp-label">{{ 'credit.collateral' | t }}</legend>
        @for (f of o.eligible; track f.farmlandId) {
          <label class="flex items-center justify-between gap-2 rounded-md border border-border px-2 py-1.5 text-[12px] text-text">
            <span class="flex items-center gap-2">
              <input type="checkbox" [checked]="selected().includes(f.farmlandId)" (change)="toggle(f.farmlandId)" data-testid="collateral-option" />
              {{ 'credit.collateralField' | t: { id: f.farmlandId, ha: (f.hectares | num: 1) } }}
            </span>
            <span class="font-mono text-muted">{{ f.collateralValue | money }}</span>
          </label>
        } @empty {
          <p class="text-[11px] text-muted" data-testid="no-collateral">{{ 'credit.noCollateral' | t }}</p>
        }
        @if (value() > 0) {
          <p class="text-[12px] text-accent" data-testid="collateral-coverage">{{ 'credit.coverage' | t: { value: (value() | money), percent: (coveragePercent() | num: 0), discount: (discountPercent() | num: 2) } }}</p>
        }
        <p class="text-[11px] text-muted" data-testid="collateral-hint">{{ 'credit.collateralHint' | t: { share: (o.requiredAboveSharePercent | num: 0), amount: (o.requiredAboveAmount | money), ltv: (o.loanToValuePercent | num: 0), discount: (o.maxInterestDiscountPercent | num: 2) } }}</p>
      </fieldset>
    }
  `,
})
export class CollateralPicker {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);

  /** The requested amount (coverage = collateral value / amount). */
  readonly amount = input(0);
  readonly changed = output<number[]>();

  readonly overview = signal<CollateralOverviewView | null>(null);
  readonly selected = signal<number[]>([]);
  readonly value = computed(() => (this.overview()?.eligible ?? []).filter((f) => this.selected().includes(f.farmlandId))
    .reduce((s, f) => s + f.collateralValue, 0));
  readonly coveragePercent = computed(() => (this.amount() > 0 ? Math.min(100, (this.value() / this.amount()) * 100) : 0));
  readonly discountPercent = computed(() => ((this.overview()?.maxInterestDiscountPercent ?? 0) * this.coveragePercent()) / 100);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.api.collateral().subscribe({
      next: (o) => {
        this.overview.set(o);
        const free = o.eligible.map((f) => f.farmlandId);
        this.selected.update((s) => s.filter((id) => free.includes(id)));
      },
      error: () => this.overview.set(null),
    });
  }

  toggle(id: number): void {
    this.selected.update((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
    this.changed.emit(this.selected());
  }

  /** After a submit: the chosen fields are tied to the application and leave the list. */
  reset(): void {
    this.selected.set([]);
    this.changed.emit([]);
    this.load();
  }
}
