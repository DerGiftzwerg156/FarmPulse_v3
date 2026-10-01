import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { FarmReportView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3 R3-K3: farm report of a finished FS25 year - income and expenses per category, profit and tax, crop and
 * yield per field, rain, stables, staff, trust and village reputation compared with the previous report. The figures
 * come from the backend; the bank advisor's comment is in her invitation mail.
 */
@Component({
  selector: 'app-farm-report-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, Card],
  template: `
    <app-card [title]="'credit.report.title' | t" data-testid="farm-report">
      @if (reports(); as list) {
        @if (list.length === 0) {
          <p class="text-sm text-muted" data-testid="no-report">{{ 'credit.report.none' | t }}</p>
        } @else {
          <div class="mb-3 flex flex-wrap gap-1.5">
            @for (r of list; track r.year) {
              <button type="button" class="rounded-md border px-2 py-0.5 text-[12px]" (click)="year.set(r.year)"
                [class.border-accent]="current()?.year === r.year" [class.border-border]="current()?.year !== r.year" data-testid="report-year">{{ 'credit.report.year' | t: { year: r.year } }}</button>
            }
          </div>
          @if (current(); as r) {
            <div class="grid grid-cols-2 gap-2 sm:grid-cols-4" data-testid="report-totals">
              <div><div class="fp-label">{{ 'credit.report.income' | t }}</div><div class="font-mono text-[13px] text-text">{{ r.totals.operatingIncome | money }}</div></div>
              <div><div class="fp-label">{{ 'credit.report.expense' | t }}</div><div class="font-mono text-[13px] text-text">{{ r.totals.operatingExpense | money }}</div></div>
              <div><div class="fp-label">{{ 'credit.report.result' | t }}</div><div class="font-mono text-[13px]" [class.text-danger]="r.totals.operatingResult < 0" [class.text-accent]="r.totals.operatingResult >= 0">{{ r.totals.operatingResult | money }}</div></div>
              <div><div class="fp-label">{{ 'credit.report.tax' | t }}</div><div class="font-mono text-[13px] text-text" data-testid="report-tax">{{ r.tax?.tax === null || r.tax?.tax === undefined ? '–' : (r.tax!.tax | money) }}</div></div>
            </div>
            <p class="mt-1 text-[11px] text-muted">{{ 'credit.report.months' | t: { n: r.months, investment: (r.totals.investment | money) } }}</p>

            <div class="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
              <div>
                <div class="fp-label mb-1">{{ 'credit.report.categories' | t }}</div>
                <ul class="space-y-0.5 text-[12px]" data-testid="report-categories">
                  @for (c of r.income.concat(r.expenses); track c.category) {
                    <li class="flex justify-between"><span class="text-text">{{ c.category | label: 'financeCategory' }}</span><span class="font-mono">{{ c.amount | money }}</span></li>
                  } @empty {
                    <li class="text-muted">{{ 'credit.report.noJournal' | t }}</li>
                  }
                </ul>
              </div>
              <div>
                <div class="fp-label mb-1">{{ 'credit.report.fields' | t }}</div>
                <ul class="space-y-0.5 text-[12px]" data-testid="report-fields">
                  @for (f of r.fields; track $index) {
                    <li class="flex justify-between"><span class="text-text">{{ 'farmland.fieldLabel' | t: { id: f.farmlandId } }} · {{ f.fruitType | label: 'fillType' }}</span>
                      <span class="font-mono">{{ f.yieldLiters === null ? (f.harvested ? ('credit.report.harvested' | t) : ('credit.report.notHarvested' | t)) : ((f.yieldLiters | num) + ' l') }}</span></li>
                  } @empty {
                    <li class="text-muted">{{ 'credit.report.noFields' | t }}</li>
                  }
                </ul>
                <p class="mt-2 text-[12px] text-text" data-testid="report-rain">{{ 'credit.report.rain' | t: { hours: (rainHours() | num) } }}</p>
              </div>
            </div>

            <div class="mt-3 border-t border-border pt-3" data-testid="report-compare">
              <div class="fp-label mb-1">{{ 'credit.report.compare' | t }}</div>
              <table class="w-full text-left text-[12px]">
                <thead><tr class="fp-label"><th></th><th class="text-right">{{ 'credit.report.year' | t: { year: r.year } }}</th>
                  <th class="text-right">{{ r.previous ? ('credit.report.previous' | t) : '' }}</th></tr></thead>
                <tbody>
                  <tr class="border-t border-border"><td class="py-1">{{ 'credit.report.staff' | t }}</td><td class="text-right font-mono">{{ r.snapshot.staff }}</td><td class="text-right font-mono text-muted">{{ r.previous?.staff ?? '' }}</td></tr>
                  <tr class="border-t border-border"><td class="py-1">{{ 'credit.report.wages' | t }}</td><td class="text-right font-mono">{{ r.snapshot.monthlyWages | money }}</td><td class="text-right font-mono text-muted">{{ r.previous ? (r.previous.monthlyWages | money) : '' }}</td></tr>
                  <tr class="border-t border-border"><td class="py-1">{{ 'credit.report.animals' | t }}</td><td class="text-right font-mono">{{ r.snapshot.animals }}@if (r.snapshot.averageHealth !== null) { · {{ r.snapshot.averageHealth | num: 0 }} % }</td><td class="text-right font-mono text-muted">{{ r.previous?.animals ?? '' }}</td></tr>
                  <tr class="border-t border-border"><td class="py-1">{{ 'credit.report.reputation' | t }}</td><td class="text-right">{{ r.snapshot.reputationTier | label: 'reputationTier' }}</td><td class="text-right text-muted">{{ r.previous ? (r.previous.reputationTier | label: 'reputationTier') : '' }}</td></tr>
                </tbody>
              </table>
              @if (r.welfareInspections > 0) {
                <p class="mt-1 text-[11px] text-warn">{{ 'credit.report.inspections' | t: { n: r.welfareInspections } }}</p>
              }
              <ul class="mt-2 flex flex-wrap gap-1.5" data-testid="report-trust">
                @for (t of r.snapshot.trust; track t.characterId) {
                  <li class="rounded-sm border border-border px-1.5 py-0.5 text-[11px] text-text">{{ t.name }}: {{ t.level | label: 'trustLevel' }}@if (previousLevel(r, t.characterId); as before) { ({{ before | label: 'trustLevel' }}) }</li>
                }
              </ul>
            </div>
          }
        }
      } @else {
        <p class="text-sm text-muted">{{ 'common.loading' | t }}</p>
      }
    </app-card>
  `,
})
export class FarmReportCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);

  readonly reports = signal<FarmReportView[] | null>(null);
  readonly year = signal<number | null>(null);
  readonly current = computed(() => {
    const list = this.reports() ?? [];
    return list.find((r) => r.year === this.year()) ?? list[0] ?? null;
  });
  readonly rainHours = computed(() => (this.current()?.rain ?? []).reduce((s, r) => s + r.rainHours, 0));

  constructor() {
    effect(() => {
      this.store.stateVersion();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.api.farmReports().subscribe({ next: (r) => this.reports.set(r), error: () => this.reports.set([]) });
  }

  previousLevel(r: FarmReportView, characterId: number): string | null {
    return r.previous?.trust.find((t) => t.characterId === characterId)?.level ?? null;
  }
}
