import { Component, DestroyRef, ElementRef, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { FinanceMonthView, FinanceOverview } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Card } from '../../shared/ui/card';
import { SERIES_COLORS } from '../../shared/ui/chart-wrapper';
import { Stat } from '../../shared/ui/stat';

/** Categories beyond the palette fold into "Sonstige" (never a generated hue). */
export const MAX_COLORED_CATEGORIES = 5;
/** Neutral gray for the folded "other" categories (muted token), never a status color. */
export const OTHER_COLOR = '#7C9088';
export const OTHER_KEY = '__OTHER__';
const SURFACE = '#141A17';
const RESULT_COLOR = '#E2ECE9';

const H = 240;
const PAD = { top: 12, bottom: 28, left: 64, right: 12 };
const BAR_MAX = 24;
const GAP = 2;
const RADIUS = 4;

/** Clean tick step (1, 2 or 5 × 10^n) for about `count` intervals. */
export function niceStep(range: number, count = 5): number {
  const raw = Math.max(range, 1) / count;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const f = raw / pow;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * pow;
}

export interface Segment {
  key: string;
  category: string;
  amount: number;
  color: string;
  y: number;
  height: number;
  /** Outermost segment of its side: gets the rounded data-end. */
  outer: boolean;
}

export interface Column {
  month: FinanceMonthView;
  x: number;
  width: number;
  segments: Segment[];
}

/** Operating lines of a month (the bars show only the operating business; investments etc. stay in the table). */
export function operatingLines(m: FinanceMonthView) {
  return m.lines.filter((l) => l.financeClass === 'OPERATING_INCOME' || l.financeClass === 'OPERATING_EXPENSE');
}

/**
 * Color slots: the categories with the largest total amount (over all shown months) keep the palette in a fixed,
 * alphabetical order; all others fold into "Sonstige".
 */
export function categoryColors(months: FinanceMonthView[]): Map<string, string> {
  const totals = new Map<string, number>();
  for (const m of months) {
    for (const l of operatingLines(m)) totals.set(l.category, (totals.get(l.category) ?? 0) + Math.abs(l.amount));
  }
  const top = [...totals.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, MAX_COLORED_CATEGORIES).map(([c]) => c).sort();
  const colors = new Map<string, string>();
  top.forEach((c, i) => colors.set(c, SERIES_COLORS[i]));
  return colors;
}

/**
 * Roadmap V2 R2-B4: farm bookkeeping on the bank page - operating income (up) and expenses (down) per game month as
 * stacked columns by category, the monthly operating result as a tick, a table view with every class and category.
 */
@Component({
  selector: 'app-finance-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, Card, Stat],
  templateUrl: './finance-card.html',
  host: { class: 'block' },
})
export class FinanceCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly overview = signal<FinanceOverview | null>(null);
  readonly failed = signal(false);
  readonly showTable = signal(false);
  readonly hover = signal<number | null>(null);
  readonly measured = signal(640);
  readonly height = H;
  readonly pad = PAD;
  readonly surface = SURFACE;
  readonly resultColor = RESULT_COLOR;
  readonly otherKey = OTHER_KEY;

  readonly months = computed(() => this.overview()?.months ?? []);
  readonly lastComplete = computed(() => [...this.months()].reverse().find((m) => m.complete) ?? null);
  readonly colors = computed(() => categoryColors(this.months()));

  constructor() {
    effect(() => {
      this.store.stateVersion();
      untracked(() => this.load());
    });
    const host = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      if (typeof ResizeObserver === 'undefined') return;
      const ro = new ResizeObserver((entries) => {
        const w = Math.round(entries[0]?.contentRect.width ?? 0);
        if (w > 0) this.measured.set(Math.max(280, w - 32));
      });
      ro.observe(host);
      destroyRef.onDestroy(() => ro.disconnect());
    });
  }

  load(): void {
    this.api.finances().subscribe({
      next: (o) => {
        this.overview.set(o);
        this.failed.set(false);
      },
      error: () => this.failed.set(true),
    });
  }

  get width(): number {
    return this.measured();
  }

  monthLabel(m: FinanceMonthView): string {
    return `${this.i18n.t(`enums.period.${m.period}`)} ${this.i18n.t('bank.finance.year', { year: m.year })}`;
  }

  shortMonth(m: FinanceMonthView): string {
    return this.i18n.t(`enums.period.${m.period}`).slice(0, 3);
  }

  categoryLabel(category: string): string {
    if (category === OTHER_KEY) return this.i18n.t('bank.finance.other');
    const key = `enums.financeCategory.${category}`;
    return this.i18n.has(key) ? this.i18n.t(key) : category;
  }

  colorOf(category: string): string {
    return this.colors().get(category) ?? OTHER_COLOR;
  }

  /** Legend: colored categories in slot order, then "Sonstige" if any category folded. */
  readonly legend = computed(() => {
    const entries = [...this.colors().entries()].map(([category, color]) => ({ key: category, color }));
    const folded = this.months().some((m) => operatingLines(m).some((l) => !this.colors().has(l.category)));
    return folded ? [...entries, { key: OTHER_KEY, color: OTHER_COLOR }] : entries;
  });

  readonly domain = computed(() => {
    let hi = 0;
    let lo = 0;
    for (const m of this.months()) {
      const lines = operatingLines(m);
      hi = Math.max(hi, lines.filter((l) => l.amount > 0).reduce((s, l) => s + l.amount, 0), m.operatingResult);
      lo = Math.min(lo, lines.filter((l) => l.amount < 0).reduce((s, l) => s + l.amount, 0), m.operatingResult);
    }
    if (hi === lo) hi = 1;
    // domain on clean tick multiples, 0 always included
    const step = niceStep(hi - lo);
    return { lo: Math.floor(lo / step) * step, hi: Math.ceil(hi / step) * step, step };
  });

  sy(v: number): number {
    const d = this.domain();
    return H - PAD.bottom - ((v - d.lo) / (d.hi - d.lo)) * (H - PAD.top - PAD.bottom);
  }

  readonly band = computed(() => (this.width - PAD.left - PAD.right) / Math.max(1, this.months().length));

  readonly columns = computed<Column[]>(() => {
    const band = this.band();
    const width = Math.max(4, Math.min(BAR_MAX, band * 0.6));
    return this.months().map((m, i) => {
      const x = PAD.left + band * i + (band - width) / 2;
      const segments: Segment[] = [];
      for (const sign of [1, -1]) {
        // fold categories without a color slot into one "Sonstige" segment per side
        const sums = new Map<string, number>();
        for (const l of operatingLines(m).filter((l) => Math.sign(l.amount) === sign)) {
          const key = this.colors().has(l.category) ? l.category : OTHER_KEY;
          sums.set(key, (sums.get(key) ?? 0) + l.amount);
        }
        const ordered = [...sums.entries()].sort((a, b) =>
          a[0] === OTHER_KEY ? 1 : b[0] === OTHER_KEY ? -1 : a[0].localeCompare(b[0]));
        let base = 0;
        ordered.forEach(([key, amount], j) => {
          const from = this.sy(base);
          const to = this.sy(base + amount);
          base += amount;
          const top = Math.min(from, to);
          const h = Math.abs(to - from);
          // 2px surface gap between touching segments (not at the baseline)
          const gapped = j === 0 ? h : Math.max(0, h - GAP);
          segments.push({ key: `${sign}-${key}`, category: key, amount, color: this.colorOf(key),
            y: sign > 0 ? top : top + (h - gapped), height: gapped, outer: j === ordered.length - 1 });
        });
      }
      return { month: m, x, width, segments };
    });
  });

  /** Path of a segment: the outermost one gets 4px rounded corners at its data-end, square at the baseline. */
  segmentPath(c: Column, s: Segment): string {
    const r = s.outer ? Math.min(RADIUS, s.height, c.width / 2) : 0;
    const x0 = c.x;
    const x1 = c.x + c.width;
    const y0 = s.y;
    const y1 = s.y + s.height;
    if (r === 0) return `M${x0},${y0}H${x1}V${y1}H${x0}Z`;
    if (s.amount > 0) {
      return `M${x0},${y1}V${y0 + r}Q${x0},${y0} ${x0 + r},${y0}H${x1 - r}Q${x1},${y0} ${x1},${y0 + r}V${y1}Z`;
    }
    return `M${x0},${y0}V${y1 - r}Q${x0},${y1} ${x0 + r},${y1}H${x1 - r}Q${x1},${y1} ${x1},${y1 - r}V${y0}Z`;
  }

  readonly yTicks = computed(() => {
    const d = this.domain();
    const ticks: number[] = [];
    for (let v = d.lo; v <= d.hi + d.step / 2; v += d.step) ticks.push(Math.round(v));
    return ticks;
  });

  /** Every second month label when the columns get narrow. */
  showTick(i: number): boolean {
    return this.band() >= 36 || i % 2 === 0;
  }

  money(v: number): string {
    return new Intl.NumberFormat('de-DE', { maximumFractionDigits: 0 }).format(Math.round(v)) + ' €';
  }

  compact(v: number): string {
    const a = Math.abs(v);
    if (a >= 1_000_000) return `${(v / 1_000_000).toLocaleString('de-DE', { maximumFractionDigits: 1 })} Mio.`;
    if (a >= 1_000) return `${(v / 1_000).toLocaleString('de-DE', { maximumFractionDigits: 0 })} Tsd.`;
    return v.toLocaleString('de-DE', { maximumFractionDigits: 0 });
  }

  /** Tooltip rows: every operating line of the hovered month with the color of its slot (gray = folded). */
  readonly hoverLines = computed(() => {
    const c = this.hovered();
    if (c === null) return [];
    return [...operatingLines(c.month)].sort((a, b) => b.amount - a.amount)
      .map((l) => ({ category: l.category, amount: l.amount, color: this.colorOf(l.category) }));
  });

  readonly hovered = computed(() => {
    const i = this.hover();
    return i === null ? null : (this.columns()[i] ?? null);
  });
}
