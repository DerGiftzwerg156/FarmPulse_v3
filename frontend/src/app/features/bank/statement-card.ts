import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { StatementEntryView, StatementMonthView, StatementView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Card } from '../../shared/ui/card';
import { Stat } from '../../shared/ui/stat';

const MS_PER_DAY = 86_400_000;
const MS_PER_HOUR = 3_600_000;

/** Category filter value for "every category". */
export const ALL = '';

/**
 * Booking statement ("Kontoauszug", owner decisions 2026-10-06): what was booked when in a game month - single
 * bookings (purchases and sales of vehicles, buildings and fields, every FarmPulse booking with its note) and the daily
 * sums of the running bookings (sales per fill type and sell point with litres). Shop vehicle purchases and sales show
 * the vehicle names when they could be assigned.
 */
@Component({
  selector: 'app-statement-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, Card, Stat],
  templateUrl: './statement-card.html',
  host: { class: 'block' },
})
export class StatementCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly view = signal<StatementView | null>(null);
  readonly failed = signal(false);
  /** Selected month as "year-period"; null = the latest month (follows new months). */
  readonly selected = signal<string | null>(null);
  readonly category = signal<string>(ALL);

  readonly months = computed(() => this.view()?.months ?? []);
  readonly entries = computed(() => this.view()?.entries ?? []);
  readonly categories = computed(() => [...new Set(this.entries().map((e) => e.category))]
    .sort((a, b) => this.categoryLabel(a).localeCompare(this.categoryLabel(b), 'de')));
  readonly shown = computed(() => {
    const c = this.category();
    return c === ALL ? this.entries() : this.entries().filter((e) => e.category === c);
  });
  readonly incoming = computed(() => this.shown().filter((e) => e.amount > 0).reduce((s, e) => s + e.amount, 0));
  readonly outgoing = computed(() => this.shown().filter((e) => e.amount < 0).reduce((s, e) => s + e.amount, 0));
  readonly balance = computed(() => this.incoming() + this.outgoing());

  constructor() {
    effect(() => {
      this.store.stateVersion();
      untracked(() => this.load());
    });
  }

  load(): void {
    const sel = this.selected();
    const [year, period] = sel === null ? [undefined, undefined] : sel.split('-').map(Number);
    this.api.statement(year, period).subscribe({
      next: (v) => {
        this.view.set(v);
        this.failed.set(false);
        if (this.category() !== ALL && !v.entries.some((e) => e.category === this.category())) this.category.set(ALL);
      },
      error: () => this.failed.set(true),
    });
  }

  monthKey(m: { year: number | null; period: number | null }): string {
    return `${m.year}-${m.period}`;
  }

  currentKey(): string {
    const v = this.view();
    return v ? this.monthKey(v) : '';
  }

  selectMonth(key: string): void {
    const latest = this.months().at(-1);
    this.selected.set(latest && this.monthKey(latest) === key ? null : key);
    this.category.set(ALL);
    this.load();
  }

  monthLabel(m: StatementMonthView): string {
    return `${this.i18n.t(`enums.period.${m.period}`)} ${this.i18n.t('bank.finance.year', { year: m.year })}`;
  }

  categoryLabel(category: string): string {
    const key = `enums.financeCategory.${category}`;
    return this.i18n.has(key) ? this.i18n.t(key) : category;
  }

  /** "2. Oktober" (day of the FS25 period), plus the time of day for a single booking. */
  dateLabel(e: StatementEntryView): string {
    const month = this.i18n.t(`enums.period.${e.period}`);
    const date = e.day != null ? `${e.day}. ${month}` : month;
    if (!e.single) return date;
    const ms = ((e.gameTime % MS_PER_DAY) + MS_PER_DAY) % MS_PER_DAY;
    const h = Math.floor(ms / MS_PER_HOUR);
    const min = Math.floor((ms % MS_PER_HOUR) / 60_000);
    return `${date}, ${String(h).padStart(2, '0')}:${String(min).padStart(2, '0')}`;
  }

  /** Vehicle line of a shop purchase / sale, null when there is nothing to show. */
  vehicleLine(e: StatementEntryView): string | null {
    switch (e.vehicleMatch) {
      case 'MATCHED':
        return e.vehicleNames;
      case 'AMBIGUOUS':
        return this.i18n.t('bank.statement.ambiguous', { names: e.vehicleNames ?? '' });
      case 'PENDING':
        return this.i18n.t('bank.statement.pending');
      default:
        return null;
    }
  }
}
