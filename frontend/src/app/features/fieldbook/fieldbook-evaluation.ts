import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import {
  FieldBookCropView,
  FieldBookEvaluationCell,
  FieldBookEvaluationView,
} from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { NumberPipe } from '../../shared/format/format.pipes';
import { Card } from '../../shared/ui/card';
import { SERIES_COLORS } from '../../shared/ui/chart-wrapper';

/** Colour of the crops beyond the palette ("Sonstige"), as in the bank chart. */
export const OTHER_COLOR = '#7C9088';
const SURFACE = '#141A17';
const GRID = '#222E28';
const AXIS = '#7C9088';

type Measure = keyof FieldBookEvaluationCell['measures'];
const MEASURES: { key: Measure; rule?: 'lime' | 'weeds' }[] = [
  { key: 'FERT1' },
  { key: 'FERT2' },
  { key: 'LIMED', rule: 'lime' },
  { key: 'ROLLED' },
  { key: 'WEEDS', rule: 'weeds' },
  { key: 'MULCHED' },
];

/** Key of a crop and its harvest product (owner decision 2026-10-08: products are kept apart). */
export function cropKey(fruitType: string | null, fillType: string | null): string {
  return `${fruitType ?? ''}|${fillType ?? ''}`;
}

/**
 * Colour per crop and product, fixed for the whole evaluation (colour follows the crop, not the field): the crops with
 * the most entries take the palette in alphabetical order; the others fold into "Sonstige" (like the bank chart).
 */
export function cropColors(view: FieldBookEvaluationView): Map<string, string> {
  const counts = new Map<string, number>();
  for (const f of view.fields) {
    for (const c of f.cells) {
      if (c.fruitType && c.litersPerHectare !== null) {
        const k = cropKey(c.fruitType, c.fillType);
        counts.set(k, (counts.get(k) ?? 0) + 1);
      }
    }
  }
  const top = [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, SERIES_COLORS.length)
    .map(([k]) => k)
    .sort();
  const colors = new Map<string, string>();
  top.forEach((k, i) => colors.set(k, SERIES_COLORS[i]));
  return colors;
}

/**
 * Roadmap V3.3 section E: tab "Auswertung" of the field book (owner decisions 2026-10-08 / 2026-10-09) - the period
 * (last x harvest years, kept per savegame), the table field x year, the bar chart l/ha per harvest year of one field
 * coloured by crop, and the comparison with / without each measure per crop and product, always with n.
 */
@Component({
  selector: 'app-fieldbook-evaluation',
  imports: [TranslatePipe, NumberPipe, Card],
  templateUrl: './fieldbook-evaluation.html',
})
export class FieldBookEvaluation {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);
  /** Crops of the field book (labels for crops of map mods). */
  readonly crops = input<FieldBookCropView[]>([]);

  readonly view = signal<FieldBookEvaluationView | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly fieldId = signal<number | null>(null);
  readonly hover = signal<number | null>(null);

  readonly width = 640;
  readonly height = 240;
  readonly pad = { left: 56, right: 12, top: 12, bottom: 28 };
  readonly surface = SURFACE;
  readonly grid = GRID;
  readonly axis = AXIS;

  readonly measures = computed(() => {
    const v = this.view();
    return MEASURES.filter((m) => !m.rule || (m.rule === 'lime' ? v?.showLime : v?.showWeeds));
  });
  /** Harvest years of the period, oldest first. */
  readonly years = computed(() => {
    const v = this.view();
    if (!v || v.fromYear === null || v.toYear === null) return [];
    const list: number[] = [];
    for (let y = v.fromYear; y <= v.toYear; y++) list.push(y);
    return list;
  });
  /** Choices of the period: 1 to the years with entries (at least the default) and "alle Jahre" (0). */
  readonly periodOptions = computed(() => {
    const v = this.view();
    if (!v) return [];
    const max = Math.max(v.defaultYears, v.yearsAvailable, v.years);
    return [...Array.from({ length: max }, (_, i) => i + 1), 0];
  });
  readonly colors = computed(() => {
    const v = this.view();
    return v ? cropColors(v) : new Map<string, string>();
  });
  /** Legend: the coloured crops in palette order, then "Sonstige" if a crop folded into it. */
  readonly legend = computed(() => {
    const v = this.view();
    if (!v) return [];
    const colors = this.colors();
    let folded = false;
    for (const f of v.fields)
      for (const c of f.cells)
        if (
          c.fruitType &&
          c.litersPerHectare !== null &&
          !colors.has(cropKey(c.fruitType, c.fillType))
        )
          folded = true;
    const entries = [...colors.entries()].map(([k, color]) => ({
      key: k,
      color,
      label: this.keyLabel(k),
    }));
    return folded
      ? [
          ...entries,
          { key: 'other', color: OTHER_COLOR, label: this.i18n.t('fieldBook.eval.other') },
        ]
      : entries;
  });
  readonly field = computed(() => {
    const v = this.view();
    if (!v || !v.fields.length) return null;
    return v.fields.find((f) => f.farmlandId === this.fieldId()) ?? v.fields[0];
  });
  /** Bars of the chosen field: one per harvest year with litres per hectare. */
  readonly bars = computed(() => {
    const f = this.field();
    const years = this.years();
    if (!f || !years.length) return [];
    const band = (this.width - this.pad.left - this.pad.right) / years.length;
    const barWidth = Math.max(4, Math.min(48, band - 2 - band * 0.3));
    return f.cells
      .filter((c) => c.litersPerHectare !== null)
      .map((c) => {
        const i = years.indexOf(c.harvestYear);
        return {
          cell: c,
          index: i,
          x: this.pad.left + band * i + (band - barWidth) / 2,
          width: barWidth,
          color: this.colors().get(cropKey(c.fruitType, c.fillType)) ?? OTHER_COLOR,
        };
      })
      .filter((b) => b.index >= 0);
  });
  readonly maxPerHa = computed(() => {
    const max = Math.max(0, ...this.bars().map((b) => b.cell.litersPerHectare ?? 0));
    return max <= 0 ? 1 : niceCeil(max);
  });
  readonly yTicks = computed(() => [0, 0.25, 0.5, 0.75, 1].map((f) => f * this.maxPerHa()));
  readonly hovered = computed(() => {
    const i = this.hover();
    return i === null ? null : (this.bars().find((b) => b.index === i) ?? null);
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.fieldBookEvaluation().subscribe({
      next: (v) => {
        this.view.set(v);
        this.error.set(null);
      },
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  choosePeriod(event: Event): void {
    const years = Number((event.target as HTMLSelectElement).value);
    this.busy.set(true);
    this.api.setFieldBookPeriod(years).subscribe({
      next: (v) => {
        this.view.set(v);
        this.error.set(null);
        this.busy.set(false);
      },
      error: (e) => {
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
        this.busy.set(false);
      },
    });
  }

  chooseField(event: Event): void {
    this.fieldId.set(Number((event.target as HTMLSelectElement).value));
    this.hover.set(null);
  }

  cellOf(row: { cells: FieldBookEvaluationCell[] }, year: number): FieldBookEvaluationCell | null {
    return row.cells.find((c) => c.harvestYear === year) ?? null;
  }

  sy(v: number): number {
    const h = this.height - this.pad.top - this.pad.bottom;
    return this.pad.top + h - (v / this.maxPerHa()) * h;
  }

  /** Bar path with 4px rounded top corners, anchored square to the baseline. */
  barPath(x: number, width: number, value: number): string {
    const base = this.sy(0);
    const top = this.sy(value);
    const r = Math.min(4, width / 2, base - top);
    return `M${x},${base} L${x},${top + r} Q${x},${top} ${x + r},${top} L${x + width - r},${top} Q${x + width},${top} ${x + width},${top + r} L${x + width},${base} Z`;
  }

  bandX(i: number): number {
    return (
      this.pad.left + ((this.width - this.pad.left - this.pad.right) / this.years().length) * i
    );
  }

  bandWidth(): number {
    return (this.width - this.pad.left - this.pad.right) / Math.max(1, this.years().length);
  }

  cropLabel(name: string | null): string {
    if (!name) return '–';
    const key = `enums.fillType.${name}`;
    if (this.i18n.has(key)) return this.i18n.t(key);
    return this.crops().find((c) => c.name === name)?.title ?? name;
  }

  /** "Weizen", or "Mais (Häckselgut)" when the product is not the crop itself. */
  label(fruitType: string | null, fillType: string | null): string {
    const crop = this.cropLabel(fruitType);
    if (!fillType || fillType === fruitType) return crop;
    const standard = this.crops().find((c) => c.name === fruitType)?.fillType;
    const product = this.cropLabel(fillType);
    if (fillType === standard || product === crop) return crop;
    return `${crop} (${product})`;
  }

  keyLabel(key: string): string {
    const [fruit, fill] = key.split('|');
    return this.label(fruit || null, fill || null);
  }

  compact(v: number): string {
    return new Intl.NumberFormat('de-DE', { maximumFractionDigits: 0 }).format(v);
  }
}

/** Round up to 1, 2 or 5 times a power of ten (axis maximum). */
export function niceCeil(v: number): number {
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 2, 5, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}
