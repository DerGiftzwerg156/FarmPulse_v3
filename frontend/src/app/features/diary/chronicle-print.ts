import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageError, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { ChronicleReport, ChronicleView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { clockTime } from '../../shared/format/format';
import { PageErrorView } from '../../shared/ui/page-error';

/** Opens the browser's print dialog (separate so tests can replace it). */
export const PRINT = { open: (): void => window.print() };

/**
 * Roadmap V3 R3-T2: print view of the farm chronicle - the same content as the Markdown file (backstory, milestones,
 * entries by game day, farm reports). Opens the browser's print dialog once loaded ("Als PDF speichern"); the print
 * stylesheet (styles.css, fp-no-print / fp-print-page) hides the tablet chrome and prints black on white.
 */
@Component({
  selector: 'app-chronicle-print',
  imports: [TranslatePipe, GameTimePipe, MoneyPipe, NumberPipe, RouterLink, PageErrorView],
  template: `
    <div class="fp-no-print mb-4 flex flex-wrap items-center justify-end gap-2" data-testid="print-actions">
      <a routerLink="/diary" class="fp-label rounded-[10px] border border-border px-3 py-1.5 hover:border-accent/60 hover:text-text">{{ 'diary.printView.back' | t }}</a>
      <button type="button" class="fp-label rounded-[10px] border border-accent px-3 py-1.5 text-accent" (click)="print()" data-testid="print-again">{{ 'diary.printView.printAgain' | t }}</button>
    </div>
    @if (error(); as err) {
      <app-page-error [error]="err" />
    } @else if (chronicle(); as c) {
      <article class="fp-print-page mx-auto max-w-3xl space-y-6 font-body text-[14px] leading-relaxed text-text" data-testid="chronicle-page">
        <header>
          <h1 class="font-display text-2xl font-bold" data-testid="chronicle-title">{{ 'diary.printView.title' | t: { name: c.farmName } }}</h1>
          <p class="text-[12px] text-muted">{{ 'diary.printView.asOf' | t: { time: (c.gameTime | gameTime) } }}</p>
        </header>

        <section class="fp-print-avoid-break">
          <h2 class="mb-2 font-display text-lg font-semibold">{{ 'diary.printView.backstory' | t }}</h2>
          <p class="whitespace-pre-line" data-testid="chronicle-backstory">{{ c.backstory || ('diary.printView.noBackstory' | t) }}</p>
        </section>

        <section>
          <h2 class="mb-2 font-display text-lg font-semibold">{{ 'diary.printView.milestones' | t }}</h2>
          @if (c.milestones.length === 0) {
            <p class="text-muted">{{ 'diary.printView.noMilestones' | t }}</p>
          }
          <ul class="list-disc space-y-1 pl-5">
            @for (m of c.milestones; track m.key) {
              <li class="fp-print-avoid-break" data-testid="chronicle-milestone">
                {{ 'common.day' | t: { day: m.gameDay } }}: <strong>{{ m.title }}</strong>@if (m.text) { – {{ m.text }} }
              </li>
            }
          </ul>
        </section>

        <section>
          <h2 class="mb-2 font-display text-lg font-semibold">{{ 'diary.printView.entries' | t }}</h2>
          @if (c.days.length === 0) {
            <p class="text-muted">{{ 'diary.printView.noEntries' | t }}</p>
          }
          @for (d of c.days; track d.gameDay) {
            <div class="mb-3" data-testid="chronicle-day">
              <h3 class="mb-1 font-display text-[15px] font-semibold">{{ 'common.day' | t: { day: d.gameDay } }}</h3>
              <ul class="space-y-1.5 pl-1">
                @for (e of d.entries; track $index) {
                  <li class="fp-print-avoid-break" data-testid="chronicle-entry">
                    <span class="font-mono text-[12px] text-muted">{{ clock(e.gameTime) }}</span> – <strong>{{ e.title }}</strong>
                    @if (e.note) {
                      <em class="text-[12px]"> ({{ 'diary.printView.note' | t }})</em>
                    } @else if (e.entryType === 'MILESTONE') {
                      <em class="text-[12px]"> ({{ 'diary.printView.milestone' | t }})</em>
                    }
                    @if (e.text) {
                      <p class="whitespace-pre-line pl-4 text-muted">{{ e.text }}</p>
                    }
                  </li>
                }
              </ul>
            </div>
          }
        </section>

        <section>
          <h2 class="mb-2 font-display text-lg font-semibold">{{ 'diary.printView.reports' | t }}</h2>
          @if (c.reports.length === 0) {
            <p class="text-muted">{{ 'diary.printView.noReports' | t }}</p>
          }
          @for (r of c.reports; track r.year) {
            <div class="fp-print-avoid-break mb-4 space-y-2" data-testid="chronicle-report">
              <h3 class="font-display text-[15px] font-semibold">{{ 'diary.printView.year' | t: { year: r.year } }}</h3>
              <p class="text-[12px] text-muted">{{ 'diary.printView.months' | t: { months: r.months } }}</p>
              <table class="w-full max-w-md text-[13px]">
                <thead><tr><th class="text-left">{{ 'diary.printView.figure' | t }}</th><th class="text-right">{{ 'diary.printView.amount' | t }}</th></tr></thead>
                <tbody>
                  <tr><td>{{ 'diary.printView.income' | t }}</td><td class="text-right font-mono">{{ r.operatingIncome | money }}</td></tr>
                  <tr><td>{{ 'diary.printView.expense' | t }}</td><td class="text-right font-mono">{{ r.operatingExpense | money }}</td></tr>
                  <tr><td>{{ 'diary.printView.result' | t }}</td><td class="text-right font-mono">{{ r.operatingResult | money }}</td></tr>
                  <tr><td>{{ 'diary.printView.profit' | t }}</td><td class="text-right font-mono">{{ r.profit === null ? taxOpen(r) : (r.profit | money) }}</td></tr>
                  <tr><td>{{ 'diary.printView.tax' | t }}</td><td class="text-right font-mono" data-testid="chronicle-tax">{{ r.tax === null ? taxOpen(r) : (r.tax | money) }}</td></tr>
                </tbody>
              </table>
              @if (r.income.length + r.expenses.length > 0) {
                <h4 class="font-semibold">{{ 'diary.printView.categories' | t }}</h4>
                <table class="w-full max-w-md text-[13px]">
                  <thead><tr><th class="text-left">{{ 'diary.printView.category' | t }}</th><th class="text-right">{{ 'diary.printView.amount' | t }}</th></tr></thead>
                  <tbody>
                    @for (l of r.income.concat(r.expenses); track $index) {
                      <tr><td>{{ l.label }}</td><td class="text-right font-mono">{{ l.amount | money }}</td></tr>
                    }
                  </tbody>
                </table>
              }
              @if (r.fields.length > 0) {
                <h4 class="font-semibold">{{ 'diary.printView.fields' | t }}</h4>
                <table class="w-full text-[13px]">
                  <thead><tr>
                    <th class="text-left">{{ 'diary.printView.fields' | t }}</th><th class="text-left">{{ 'diary.printView.crop' | t }}</th>
                    <th class="text-right">{{ 'diary.printView.area' | t }}</th><th class="text-right">{{ 'diary.printView.yield' | t }}</th>
                  </tr></thead>
                  <tbody>
                    @for (f of r.fields; track $index) {
                      <tr data-testid="chronicle-field">
                        <td>{{ 'diary.printView.field' | t: { id: f.farmlandId } }}</td><td>{{ f.fruit }}</td>
                        <td class="text-right font-mono">{{ f.hectares === null ? '–' : (f.hectares | num: 1) + ' ha' }}</td>
                        <td class="text-right font-mono">{{ fieldYield(f) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            </div>
          }
        </section>
      </article>
    } @else {
      <p class="text-sm text-muted">{{ 'common.loading' | t }}</p>
    }
  `,
})
export class ChroniclePrint {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly destroyRef = inject(DestroyRef);

  readonly chronicle = signal<ChronicleView | null>(null);
  readonly error = signal<PageError | null>(null);

  constructor() {
    this.api.chronicle().subscribe({
      next: (c) => {
        this.chronicle.set(c);
        // after the view rendered the content
        const timer = setTimeout(() => PRINT.open(), 0);
        this.destroyRef.onDestroy(() => clearTimeout(timer));
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  print(): void {
    PRINT.open();
  }

  clock(gameTime: number): string {
    return clockTime(gameTime);
  }

  taxOpen(r: ChronicleReport): string {
    return r.taxStatus === null ? '–' : this.i18n.t('diary.printView.open');
  }

  fieldYield(f: ChronicleReport['fields'][number]): string {
    if (f.withered) return this.i18n.t('diary.printView.withered');
    if (!f.harvested) return this.i18n.t('diary.printView.notHarvested');
    return f.yieldLiters === null ? this.i18n.t('diary.printView.harvested') : `${new Intl.NumberFormat('de-DE').format(f.yieldLiters)} l`;
  }
}
