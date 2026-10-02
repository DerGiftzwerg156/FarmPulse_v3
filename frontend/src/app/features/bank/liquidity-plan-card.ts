import { Component, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { LiquidityPlanView, PlanMonth } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3 R3-K2: liquidity plan over the next FS25 months - the known postings of each month start (salaries,
 * installments, contracts, retirement payment, tax prepayments), the income as a marked estimate and the balance at
 * the month end, with the month the balance falls below zero or below the reserve.
 */
@Component({
  selector: 'app-liquidity-plan-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, Card],
  template: `
    <app-card [title]="'credit.plan.title' | t" data-testid="liquidity-plan">
      @if (plan(); as p) {
        @if (!p.available) {
          <p class="text-sm text-muted" data-testid="plan-unavailable">{{ 'credit.plan.unavailable' | t }}</p>
        } @else {
          @if (p.firstBelowZero; as m) {
            <p class="mb-2 rounded-sm border border-danger/50 px-2 py-1.5 text-[12px] text-danger" data-testid="plan-below-zero">{{ 'credit.plan.belowZero' | t: { month: monthName(m) } }}</p>
          } @else if (p.firstBelowReserve; as m) {
            <p class="mb-2 rounded-sm border border-warn/40 px-2 py-1.5 text-[12px] text-warn" data-testid="plan-below-reserve">{{ 'credit.plan.belowReserve' | t: { month: monthName(m) } }}</p>
          }
          <table class="w-full text-left text-[12px]" data-testid="plan-table">
            <thead><tr class="fp-label"><th class="py-1">{{ 'credit.plan.month' | t }}</th><th class="text-right">{{ 'credit.plan.known' | t }}</th>
              <th class="text-right">{{ 'credit.plan.income' | t }}</th><th class="text-right">{{ 'credit.plan.balance' | t }}</th></tr></thead>
            <tbody>
              @for (m of p.months; track m.monthIndex) {
                <tr class="cursor-pointer border-t border-border" (click)="toggle(m.monthIndex)" data-testid="plan-month">
                  <td class="py-1 text-text">{{ monthName(m) }}</td>
                  <td class="text-right font-mono">{{ m.knownTotal | money }}</td>
                  <td class="text-right font-mono text-muted">{{ m.incomeEstimate === null ? '–' : ('~ ' + (m.incomeEstimate | money)) }}</td>
                  <td class="text-right font-mono" [class.text-danger]="m.belowZero" [class.text-warn]="!m.belowZero && m.belowReserve">{{ m.balanceEnd | money }}</td>
                </tr>
                @if (open() === m.monthIndex) {
                  <tr data-testid="plan-postings"><td colspan="4" class="pb-2">
                    <ul class="space-y-0.5 pl-2 text-[11px] text-muted">
                      @for (x of m.postings; track $index) {
                        <li data-testid="plan-posting">{{ x.kind | label: 'planPosting' }}@if (x.label) { · {{ postingLabel(x.kind, x.label) }} }: <span class="font-mono">{{ x.amount | money }}</span>@if (x.estimate) { ({{ 'credit.plan.estimate' | t }}) }</li>
                      } @empty {
                        <li>{{ 'credit.plan.noPostings' | t }}</li>
                      }
                      <li>{{ 'credit.plan.reserve' | t: { amount: (m.reserve | money) } }}</li>
                    </ul>
                  </td></tr>
                }
              }
            </tbody>
          </table>
          <p class="mt-2 text-[11px] text-muted">{{ (p.journalAvailable ? 'credit.plan.hint' : 'credit.plan.hintNoJournal') | t }}</p>
        }
      } @else {
        <p class="text-sm text-muted">{{ 'common.loading' | t }}</p>
      }
    </app-card>
  `,
})
export class LiquidityPlanCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly plan = signal<LiquidityPlanView | null>(null);
  readonly open = signal<number | null>(null);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.api.liquidityPlan().subscribe({ next: (p) => this.plan.set(p), error: () => this.plan.set(null) });
  }

  toggle(idx: number): void {
    this.open.update((v) => (v === idx ? null : idx));
  }

  monthName(m: PlanMonth): string {
    return m.period === null ? `#${m.monthIndex}` : `${this.enumLabel(String(m.period), 'period')}${m.year !== null ? ' ' + m.year : ''}`;
  }

  private enumLabel(value: string, group: string): string {
    const key = `enums.${group}.${value}`;
    return this.i18n.has(key) ? this.i18n.t(key) : value;
  }

  postingLabel(kind: string, label: string): string {
    if (kind === 'CONTRACT') {
      const [k, field] = label.split(':');
      return this.enumLabel(k, 'contractKind') + (field ? ` ${field}` : '');
    }
    return label;
  }
}
