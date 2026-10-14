import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { FieldMarginEntry, FieldMarginKind, FieldMarginLine, FieldMarginView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { SERIES_COLORS } from '../../shared/ui/chart-wrapper';
import { OTHER_COLOR, cropKey } from '../fieldbook/fieldbook-evaluation';

const SURFACE = '#141A17';
const GRID = '#222E28';
const AXIS = '#7C9088';
const POSITIVE = '#7FD1A8';
const NEGATIVE = '#E57373';

/** Rows of the calculation in the order of the roadmap (D table); `total` rows are printed bold. */
export const TOTAL_ROWS: { key: keyof FieldMarginEntry['totals']; total?: boolean }[] = [
  { key: 'revenue' },
  { key: 'directCosts' },
  { key: 'freeOfDirectCosts', total: true },
  { key: 'contractor' },
  { key: 'margin', total: true },
  { key: 'premiums' },
  { key: 'rent' },
  { key: 'overheads' },
  { key: 'result', total: true },
];

/**
 * Roadmap V4 R4-D5: colour per crop and product with the rule of the field book evaluation (cropColors): the crops of
 * the most entries with l/ha take the palette in alphabetical order, the others are "Sonstige".
 */
export function marginCropColors(view: FieldMarginView): Map<string, string> {
  const counts = new Map<string, number>();
  for (const f of view.fields) {
    for (const e of f.entries) {
      if (e.fruitType && e.liters !== null && e.hectares !== null && e.hectares > 0) {
        const k = cropKey(e.fruitType, e.fillType);
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
 * D4: the amount of a corrected line - the amount when given, else litres x price per 1000 l (the litres of the line
 * when none are given); null = only the litres change (the backend scales the measured amount).
 */
export function lineAmount(liters: number | null, per1000: number | null, amount: number | null, lineLiters: number | null): number | null {
  if (amount !== null) return amount;
  const l = liters ?? lineLiters;
  return per1000 !== null && l !== null ? (l * per1000) / 1000 : null;
}

/** Bars of the chart: one per field with an entry of the year, positive up, negative down (euro per hectare). */
export function marginBars(
  view: FieldMarginView,
  year: number,
  width: number,
  height: number,
  pad: { left: number; right: number; top: number; bottom: number },
): { farmlandId: number; name: string; value: number; x: number; y: number; w: number; h: number }[] {
  const values = view.fields
    .map((f) => ({ f, e: f.entries.find((x) => x.harvestYear === year) }))
    .filter((x) => x.e?.perHectare?.margin !== null && x.e?.perHectare?.margin !== undefined)
    .map((x) => ({ farmlandId: x.f.farmlandId, name: x.f.name, value: x.e!.perHectare!.margin as number }));
  if (!values.length) return [];
  const max = Math.max(1, ...values.map((v) => Math.abs(v.value)));
  const plotH = height - pad.top - pad.bottom;
  const zero = pad.top + plotH / 2;
  const band = (width - pad.left - pad.right) / values.length;
  const w = Math.max(4, Math.min(40, band * 0.6));
  return values.map((v, i) => {
    const h = (Math.abs(v.value) / max) * (plotH / 2);
    return { ...v, x: pad.left + band * i + (band - w) / 2, y: v.value >= 0 ? zero - h : zero, w, h };
  });
}

/**
 * Roadmap V4 section D (owner decisions 2026-10-13): card "Deckungsbeitrag" in the app "Bank". The period is the one of
 * the field book evaluation (changed here or there, it counts for both). Table field x harvest year with the margin per
 * hectare, ranking of the fields, averages per crop, a bar chart of one year, and the calculation of one entry with its
 * lines - correctable, own lines can be added. All figures come from the backend.
 */
@Component({
  selector: 'app-margin-card',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, Card, Badge, Button],
  host: { class: 'block' },
  template: `
    <app-card [title]="'margin.title' | t" data-testid="margin-card">
      @if (error(); as e) {
        <p class="mb-2 text-[12px] text-danger" data-testid="margin-error">{{ e }}</p>
      }
      @if (view(); as v) {
        <div class="mb-3 flex flex-wrap items-center gap-3 text-[12px]">
          <select class="rounded-md border border-border bg-bg px-2 py-1 text-text" [disabled]="busy()" (change)="choosePeriod($event)" data-testid="margin-years">
            @for (o of periodOptions(); track o) {
              <option [value]="o" [selected]="o === v.years">{{ o === 0 ? ('fieldBook.eval.allYears' | t) : o === 1 ? ('fieldBook.eval.lastYear' | t) : ('fieldBook.eval.lastYears' | t: { years: o }) }}</option>
            }
          </select>
          @if (v.fromYear !== null && v.toYear !== null) {
            <span class="text-muted">{{ 'fieldBook.eval.range' | t: { from: v.fromYear, to: v.toYear } }}</span>
          }
          <label class="flex items-center gap-1.5 text-text">
            <input type="checkbox" [checked]="v.overheads" [disabled]="busy()" (change)="toggleOverheads($event)" data-testid="margin-overheads" />
            {{ 'margin.overheads' | t }}
          </label>
        </div>
        @if (!v.measured) {
          <p class="mb-2 rounded-md border border-warn/40 px-2 py-1.5 text-[12px] text-warn" data-testid="margin-estimated">{{ 'margin.estimatedHint' | t }}</p>
        }
        @if (!v.fields.length) {
          <p class="text-sm text-muted" data-testid="margin-empty">{{ 'margin.empty' | t }}</p>
        } @else {
          <!-- table field x harvest year, margin per hectare -->
          <div class="overflow-x-auto">
            <table class="w-full text-[12px]" [style.min-width.px]="160 + years().length * 110">
              <thead>
                <tr class="text-left font-display text-[10px] uppercase tracking-[0.14em] text-muted">
                  <th class="py-1.5 pr-2 font-bold">{{ 'fieldBook.col.field' | t }}</th>
                  <th class="py-1.5 pr-2 font-bold">{{ 'margin.running' | t }}</th>
                  @for (y of years(); track y) {
                    <th class="py-1.5 pr-2 font-bold">{{ y }}</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (f of v.fields; track f.farmlandId) {
                  <tr class="border-t border-border align-top" data-testid="margin-row">
                    <td class="py-1.5 pr-2 text-text">{{ f.name }}</td>
                    <td class="py-1.5 pr-2">
                      @if (f.running; as r) {
                        <button type="button" class="text-left" (click)="select(r.entryId)" data-testid="margin-running">
                          <span class="text-text"><span class="mr-1 inline-block h-2 w-2 rounded-full" [style.background]="colorOf(r)" data-testid="margin-color"></span>{{ r.fruitType | label: 'fillType' }}</span>
                          <span class="block font-mono text-muted">{{ r.perHectare ? (r.perHectare.directCosts | money) : '–' }} / ha</span>
                          @if (expectedPerHa(f.farmlandId); as x) {
                            <span class="block font-mono text-accent" data-testid="margin-expected">{{ 'margin.expected' | t: { amount: (x | money) } }}</span>
                          }
                        </button>
                      }
                    </td>
                    @for (y of years(); track y) {
                      <td class="py-1.5 pr-2">
                        @if (entryOf(f.entries, y); as e) {
                          <button type="button" class="text-left" (click)="select(e.entryId)" [class.underline]="selectedId() === e.entryId" data-testid="margin-cell">
                            <span class="text-text"><span class="mr-1 inline-block h-2 w-2 rounded-full" [style.background]="colorOf(e)" data-testid="margin-color"></span>{{ e.fruitType | label: 'fillType' }}</span>
                            <span class="block font-mono" [class.text-danger]="(e.perHectare?.margin ?? 0) < 0" [class.text-accent]="(e.perHectare?.margin ?? 0) > 0">{{ e.perHectare?.margin === null || e.perHectare?.margin === undefined ? '–' : (e.perHectare!.margin | money) }} / ha</span>
                          </button>
                        }
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>

          <div class="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div data-testid="margin-ranking">
              <div class="fp-label mb-1">{{ 'margin.ranking' | t }}</div>
              <ol class="space-y-0.5 text-[12px]">
                @for (r of v.ranking; track r.farmlandId; let i = $index) {
                  <li class="flex justify-between gap-2">
                    <span class="text-text">{{ i + 1 }}. {{ r.name }}@if (r.marginPerHectare !== null && i === 0) { <app-badge variant="positive">{{ 'margin.best' | t }}</app-badge>}@if (r.marginPerHectare !== null && i === lastRanked()) { <app-badge variant="warning">{{ 'margin.weakest' | t }}</app-badge>}</span>
                    <span class="font-mono">{{ r.marginPerHectare === null ? '–' : (r.marginPerHectare | money) + ' / ha' }} <span class="text-muted">n = {{ r.n }}</span></span>
                  </li>
                }
              </ol>
            </div>
            <div data-testid="margin-crops">
              <div class="fp-label mb-1">{{ 'margin.crops' | t }}</div>
              <table class="w-full text-[12px]">
                <thead><tr class="fp-label text-left"><th></th><th class="text-right">{{ 'margin.revenueHa' | t }}</th><th class="text-right">{{ 'margin.directHa' | t }}</th><th class="text-right">{{ 'margin.marginHa' | t }}</th><th class="text-right">n</th></tr></thead>
                <tbody>
                  @for (c of v.crops; track c.fruitType + (c.fillType ?? '')) {
                    <tr class="border-t border-border">
                      <td class="py-1 text-text">{{ c.fruitType | label: 'fillType' }}@if (c.fillType && c.fillType !== c.fruitType) { ({{ c.fillType | label: 'fillType' }})}</td>
                      <td class="text-right font-mono">{{ c.revenuePerHectare === null ? '–' : (c.revenuePerHectare | money) }}</td>
                      <td class="text-right font-mono">{{ c.directCostsPerHectare === null ? '–' : (c.directCostsPerHectare | money) }}</td>
                      <td class="text-right font-mono" [class.text-danger]="(c.marginPerHectare ?? 0) < 0">{{ c.marginPerHectare === null ? '–' : (c.marginPerHectare | money) }}</td>
                      <td class="text-right font-mono">{{ c.n }}</td>
                    </tr>
                  } @empty {
                    <tr><td colspan="5" class="text-muted">{{ 'margin.noCrops' | t }}</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>

          @if (years().length) {
            <div class="mt-4" data-testid="margin-chart">
              <div class="mb-1 flex items-center gap-2 text-[12px]">
                <span class="fp-label">{{ 'margin.chart' | t }}</span>
                <select class="rounded-md border border-border bg-bg px-2 py-0.5 text-text" (change)="chartYear.set(+$any($event.target).value)" data-testid="margin-chart-year">
                  @for (y of years(); track y) {
                    <option [value]="y" [selected]="y === shownYear()">{{ y }}</option>
                  }
                </select>
              </div>
              @if (bars().length) {
                <svg [attr.viewBox]="'0 0 ' + width + ' ' + height" class="w-full" role="img" [attr.aria-label]="'margin.chartLabel' | t: { year: shownYear() }" [style.background]="surface">
                  <line [attr.x1]="pad.left" [attr.x2]="width - pad.right" [attr.y1]="zeroY" [attr.y2]="zeroY" [attr.stroke]="grid" />
                  @for (b of bars(); track b.farmlandId) {
                    <rect [attr.x]="b.x" [attr.y]="b.y" [attr.width]="b.w" [attr.height]="b.h" [attr.fill]="b.value >= 0 ? positive : negative" rx="3" data-testid="margin-bar">
                      <title>{{ b.name }}: {{ b.value | money }} / ha</title>
                    </rect>
                    <text [attr.x]="b.x + b.w / 2" [attr.y]="height - 8" text-anchor="middle" font-size="10" [attr.fill]="axis">{{ b.name }}</text>
                  }
                </svg>
              } @else {
                <p class="text-[12px] text-muted">{{ 'margin.noBars' | t }}</p>
              }
            </div>
          }
        }

        @if (selected(); as e) {
          <div class="mt-4 border-t border-border pt-3" data-testid="margin-detail">
            <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
              <div class="text-[13px] text-text">
                {{ e.fieldName ?? e.farmlandId }} · {{ e.fruitType | label: 'fillType' }} ·
                {{ e.harvestYear === null ? ('margin.running' | t) : ('margin.harvestYear' | t: { year: e.harvestYear }) }}
                @if (e.hectares !== null) { · {{ e.hectares | num: 2 }} ha }
              </div>
              <div class="flex items-center gap-1.5">
                <a routerLink="/feldbuch/dokumentation" [queryParams]="{ entry: e.entryId }" class="text-[11px] text-accent hover:underline" data-testid="margin-fieldbook-link">{{ 'margin.fieldBookLink' | t }} →</a>
                @if (e.locked) { <app-badge variant="neutral" data-testid="margin-locked">{{ 'margin.locked' | t }}</app-badge> }
                @if (e.estimated) { <app-badge variant="warning">{{ 'margin.estimated' | t }}</app-badge> }
                @if (e.incomplete) { <app-badge variant="warning">{{ 'margin.incomplete' | t }}</app-badge> }
              </div>
            </div>
            @if (e.status === 'RUNNING' && expectedOf(e.farmlandId); as x) {
              <!-- R4-D5 (owner decision 2026-10-14): the expected revenue of the plan, else of the crop that stands -->
              <div class="mb-2 text-[12px] text-text" data-testid="margin-expected-revenue">
                {{ 'margin.expectedRevenue' | t: { amount: x.revenue === null ? '–' : (x.revenue | money), lph: (x.litersPerHa | num),
                  price: x.pricePerLiter === null ? '–' : ((x.pricePerLiter * 1000) | money) } }}
                <span class="text-muted">({{ ('margin.expectedBasis.' + x.basis) | t }})</span>
              </div>
            }
            @if (e.status !== 'RUNNING') {
              <div class="mb-2 flex flex-wrap items-center gap-2 text-[12px]" data-testid="margin-price">
                <span class="text-muted">{{ 'margin.price' | t }}:</span>
                <span class="font-mono text-text">{{ e.price.perLiter === null ? '–' : (e.price.perLiter * 1000 | money) + ' / 1000 l' }}</span>
                @if (e.price.source) { <span class="text-muted">({{ e.price.source | label: 'marginPriceSource' }}@if (e.price.provisional) {, {{ 'margin.provisional' | t }}})</span> }
                @if (!e.locked) {
                  <input class="fp-input w-28 font-mono" type="number" min="0" step="any" [placeholder]="'margin.pricePlaceholder' | t" #price data-testid="margin-price-input" />
                  <app-button variant="secondary" [disabled]="busy()" (pressed)="setPrice(e, price.value)" data-testid="margin-price-save">{{ 'common.save' | t }}</app-button>
                  @if (e.price.source === 'ENTERED') {
                    <app-button variant="secondary" [disabled]="busy()" (pressed)="resetPrice(e)" data-testid="margin-price-reset">↺</app-button>
                  }
                }
              </div>
            }
            <table class="w-full text-[12px]" data-testid="margin-lines">
              <thead><tr class="fp-label text-left"><th>{{ 'margin.line' | t }}</th><th class="text-right">{{ 'margin.liters' | t }}</th><th class="text-right">{{ 'margin.amount' | t }}</th><th></th></tr></thead>
              <tbody>
                @for (l of e.lines; track $index) {
                  <tr class="border-t border-border" [class.opacity-60]="l.held || (l.kind === 'OVERHEAD' && !v.overheads)" data-testid="margin-line">
                    <td class="py-1 text-text">
                      {{ l.kind | label: 'marginKind' }}@if (l.fillType) { · {{ l.fillType | label: 'fillType' }}}@if (l.note) { · {{ l.note }}}
                      <span class="text-muted"> ({{ l.source | label: 'marginSource' }}@if (l.corrected) {, {{ 'margin.corrected' | t }}}@if (l.held) {, {{ 'margin.held' | t }}})</span>
                    </td>
                    <td class="text-right font-mono">{{ l.liters === null ? '' : (l.liters | num) + ' l' }}@if (l.helperLiters) {<span class="text-muted"> ({{ 'margin.helper' | t: { liters: (l.helperLiters | num) } }})</span>}</td>
                    <td class="text-right font-mono" [class.text-danger]="(l.amount ?? 0) < 0">{{ l.amount === null ? ('margin.noPrice' | t) : (l.amount | money) }}</td>
                    <td class="pl-2 text-right">
                      @if (!e.locked && l.source !== 'DISTRIBUTED') {
                        @if (editing() === key(e, l, $index)) {
                          <input class="fp-input w-20 font-mono" type="number" min="0" step="any" [placeholder]="'margin.litersLine' | t" #liters data-testid="margin-liters-input" />
                          <input class="fp-input w-20 font-mono" type="number" min="0" step="any" [placeholder]="'margin.priceLine' | t" #per1000 data-testid="margin-line-price-input" />
                          <input class="fp-input w-24 font-mono" type="number" min="0" step="any" [placeholder]="'margin.amountLine' | t" #amount data-testid="margin-amount-input" />
                          <app-button variant="secondary" [disabled]="busy()" (pressed)="saveLine(e, l, amount.value, liters.value, per1000.value)" data-testid="margin-amount-save">{{ 'common.save' | t }}</app-button>
                        } @else {
                          <button type="button" class="text-[11px] text-accent" (click)="editing.set(key(e, l, $index))" data-testid="margin-edit">{{ 'margin.edit' | t }}</button>
                        }
                        @if (l.id !== null && l.corrected && l.source !== 'ENTERED') {
                          <button type="button" class="ml-1 text-[11px] text-muted" (click)="resetLine(l)" data-testid="margin-reset">↺</button>
                        }
                        @if (l.id !== null && l.source === 'ENTERED') {
                          <button type="button" class="ml-1 text-[11px] text-danger" (click)="removeLine(l)" data-testid="margin-remove">✕</button>
                        }
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="4" class="py-1 text-muted">{{ 'margin.noLines' | t }}</td></tr>
                }
              </tbody>
            </table>

            <table class="mt-3 w-full text-[12px]" data-testid="margin-totals">
              <thead><tr class="fp-label text-left"><th></th><th class="text-right">€</th><th class="text-right">€ / ha</th></tr></thead>
              <tbody>
                @for (row of totalRows; track row.key) {
                  @if (row.key !== 'overheads' || v.overheads) {
                    <tr class="border-t border-border" [class.font-bold]="row.total" [attr.data-testid]="'margin-total-' + row.key">
                      <td class="py-1 text-text">{{ 'margin.total.' + row.key | t }}</td>
                      <td class="text-right font-mono" [class.text-danger]="(e.totals[row.key] ?? 0) < 0">{{ e.totals[row.key] === null ? '–' : (e.totals[row.key]! | money) }}</td>
                      <td class="text-right font-mono">{{ e.perHectare && e.perHectare[row.key] !== null ? (e.perHectare[row.key]! | money) : '–' }}</td>
                    </tr>
                  }
                }
              </tbody>
            </table>

            @if (!e.locked) {
              <form class="mt-3 flex flex-wrap items-end gap-2 text-[12px]" (submit)="$event.preventDefault(); addLine(e, kind.value, note.value, add.value, addLiters.value, addPrice.value)" data-testid="margin-add">
                <label class="space-y-1"><span class="fp-label block">{{ 'margin.addKind' | t }}</span>
                  <select class="rounded-md border border-border bg-bg px-2 py-1 text-text" #kind data-testid="margin-add-kind">
                    @for (k of addKinds(e); track k) { <option [value]="k">{{ k | label: 'marginKind' }}</option> }
                  </select>
                </label>
                <label class="space-y-1"><span class="fp-label block">{{ 'margin.note' | t }}</span><input class="fp-input" type="text" maxlength="200" #note data-testid="margin-add-note" /></label>
                <label class="space-y-1"><span class="fp-label block">{{ 'margin.litersLine' | t }}</span><input class="fp-input w-24 font-mono" type="number" min="0" step="any" #addLiters data-testid="margin-add-liters" /></label>
                <label class="space-y-1"><span class="fp-label block">{{ 'margin.priceLine' | t }}</span><input class="fp-input w-24 font-mono" type="number" min="0" step="any" #addPrice data-testid="margin-add-price" /></label>
                <label class="space-y-1"><span class="fp-label block">{{ 'margin.amount' | t }}</span><input class="fp-input w-28 font-mono" type="number" min="0" step="any" #add data-testid="margin-add-amount" /></label>
                <app-button type="submit" [disabled]="busy()" data-testid="margin-add-submit">{{ 'margin.add' | t }}</app-button>
              </form>
            }
          </div>
        }
        <p class="mt-3 text-[11px] text-muted">{{ 'margin.hint' | t }}</p>
      }
    </app-card>
  `,
})
export class MarginCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** Roadmap V4 R4-D5: entry whose calculation opens (link `?entry=` from the field book). */
  readonly openEntry = input<number | null>(null);
  readonly view = signal<FieldMarginView | null>(null);
  readonly colors = computed(() => {
    const v = this.view();
    return v ? marginCropColors(v) : new Map<string, string>();
  });
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly selectedId = signal<number | null>(null);
  readonly editing = signal<string | null>(null);
  readonly chartYear = signal<number | null>(null);

  readonly totalRows = TOTAL_ROWS;
  readonly width = 640;
  readonly height = 220;
  readonly pad = { left: 12, right: 12, top: 12, bottom: 24 };
  readonly zeroY = this.pad.top + (this.height - this.pad.top - this.pad.bottom) / 2;
  readonly surface = SURFACE;
  readonly grid = GRID;
  readonly axis = AXIS;
  readonly positive = POSITIVE;
  readonly negative = NEGATIVE;

  /** Harvest years of the period, oldest first. */
  readonly years = computed(() => {
    const v = this.view();
    if (!v || v.fromYear === null || v.toYear === null) return [];
    const list: number[] = [];
    for (let y = v.fromYear; y <= v.toYear; y++) list.push(y);
    return list;
  });
  readonly periodOptions = computed(() => {
    const v = this.view();
    if (!v) return [];
    const max = Math.max(v.defaultYears, v.years, this.years().length);
    return [...Array.from({ length: max }, (_, i) => i + 1), 0];
  });
  readonly shownYear = computed(() => this.chartYear() ?? this.years().at(-1) ?? 0);
  readonly bars = computed(() => {
    const v = this.view();
    return v ? marginBars(v, this.shownYear(), this.width, this.height, this.pad) : [];
  });
  /** Index of the weakest field with a margin in the ranking. */
  readonly lastRanked = computed(() => {
    const r = this.view()?.ranking ?? [];
    let last = -1;
    r.forEach((x, i) => {
      if (x.marginPerHectare !== null) last = i;
    });
    return last > 0 ? last : -1;
  });
  readonly selected = computed(() => {
    const v = this.view();
    const id = this.selectedId();
    if (!v || id === null) return null;
    for (const f of v.fields) {
      if (f.running?.entryId === id) return f.running;
      const e = f.entries.find((x) => x.entryId === id);
      if (e) return e;
    }
    return null;
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
    // the calculation of the entry the field book links to opens once the card has its data
    effect(() => {
      const id = this.openEntry();
      if (id !== null && this.view()) untracked(() => this.selectedId.set(id));
    });
  }

  colorOf(e: FieldMarginEntry): string {
    return this.colors().get(cropKey(e.fruitType, e.fillType)) ?? OTHER_COLOR;
  }

  expectedOf(farmlandId: number) {
    return this.view()?.expected?.[farmlandId] ?? null;
  }

  /** Expected revenue per hectare of a running season (null without a plan or without a price). */
  expectedPerHa(farmlandId: number): number | null {
    const x = this.expectedOf(farmlandId);
    return x && x.revenue !== null && x.hectares ? x.revenue / x.hectares : null;
  }

  load(): void {
    this.api.fieldMargin().subscribe({
      next: (v) => this.show(v),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  private show(v: FieldMarginView): void {
    this.view.set(v);
    this.error.set(null);
    this.busy.set(false);
    this.editing.set(null);
  }

  private fail(e: unknown): void {
    this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
    this.busy.set(false);
  }

  private run(call: ReturnType<ApiService['fieldMargin']>): void {
    this.busy.set(true);
    call.subscribe({ next: (v) => this.show(v), error: (e) => this.fail(e) });
  }

  entryOf(entries: FieldMarginEntry[], year: number): FieldMarginEntry | null {
    return entries.find((e) => e.harvestYear === year) ?? null;
  }

  select(entryId: number): void {
    this.selectedId.set(this.selectedId() === entryId ? null : entryId);
    this.editing.set(null);
  }

  key(e: FieldMarginEntry, l: FieldMarginLine, i: number): string {
    return `${e.entryId}:${l.id ?? 'est' + i}`;
  }

  /** The period is the one of the field book evaluation (shared, owner decision 2026-10-13). */
  choosePeriod(event: Event): void {
    const years = Number((event.target as HTMLSelectElement).value);
    this.busy.set(true);
    this.api.setFieldBookPeriod(years).subscribe({ next: () => this.load(), error: (e) => this.fail(e) });
  }

  toggleOverheads(event: Event): void {
    this.run(this.api.setFieldMarginOverheads((event.target as HTMLInputElement).checked));
  }

  /**
   * D4: litres, price per 1000 l or amount of a line (the amount wins, else litres x price; litres alone scale the
   * measured amount). An estimated line has no id: its correction is an own line of the same kind that replaces it.
   */
  saveLine(e: FieldMarginEntry, l: FieldMarginLine, value: string, litersValue = '', priceValue = ''): void {
    const num = (v: string): number | null | undefined => {
      if (v.trim() === '') return null;
      const n = Number(v);
      return Number.isFinite(n) && n >= 0 ? n : undefined;
    };
    const amountIn = num(value);
    const liters = num(litersValue);
    const per1000 = num(priceValue);
    if (amountIn === undefined || liters === undefined || per1000 === undefined) return;
    const amount = lineAmount(liters, per1000, amountIn, l.liters);
    if (amount === null && liters === null) return;
    if (l.id === null) {
      if (amount === null) return;
      this.run(this.api.addFieldMarginLine(e.entryId, l.kind, null, amount, liters));
    } else {
      this.run(this.api.correctFieldMarginLine(l.id, liters, amount));
    }
  }

  resetLine(l: FieldMarginLine): void {
    if (l.id !== null) this.run(this.api.correctFieldMarginLine(l.id, null, null));
  }

  removeLine(l: FieldMarginLine): void {
    if (l.id !== null) this.run(this.api.removeFieldMarginLine(l.id));
  }

  addKinds(e: FieldMarginEntry): FieldMarginKind[] {
    return e.estimated ? ['EXTRA_COST', 'EXTRA_REVENUE', 'SEED', 'FERTILIZER', 'LIME', 'PROTECTION'] : ['EXTRA_COST', 'EXTRA_REVENUE'];
  }

  /** D4: an own line with an amount, or with litres and price per 1000 l ("Leistung hinzufügen", e.g. pressed straw). */
  addLine(e: FieldMarginEntry, kind: string, note: string, value: string, litersValue = '', priceValue = ''): void {
    const liters = litersValue.trim() === '' ? null : Number(litersValue);
    const per1000 = priceValue.trim() === '' ? null : Number(priceValue);
    const given = value.trim() === '' ? null : Number(value);
    if ([liters, per1000, given].some((x) => x !== null && (!Number.isFinite(x) || x < 0))) return;
    const amount = lineAmount(liters, per1000, given, null);
    if (amount === null) return;
    this.run(this.api.addFieldMarginLine(e.entryId, kind as FieldMarginKind, note.trim() || null, amount, liters));
  }

  /** The field takes euro per 1000 l like the prices of the Agrarbörse. */
  setPrice(e: FieldMarginEntry, value: string): void {
    const per1000 = Number(value);
    if (value === '' || !Number.isFinite(per1000) || per1000 < 0) return;
    this.run(this.api.setFieldMarginPrice(e.entryId, per1000 / 1000));
  }

  resetPrice(e: FieldMarginEntry): void {
    this.run(this.api.setFieldMarginPrice(e.entryId, null));
  }
}
