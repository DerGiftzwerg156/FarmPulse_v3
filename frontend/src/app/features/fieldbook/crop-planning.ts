import { Component, ElementRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CropPlanCheck, CropPlanCrop, CropPlanField, CropPlanningView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

export const FALLOW = 'BRACHE';

/** Months offered for sowing: the windows of the crop, every month without them (older mod). */
export function sowMonths(crop: CropPlanCrop | undefined): number[] {
  return crop?.plantablePeriods?.length ? crop.plantablePeriods : [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
}

/** Harvest products of a crop: the standard product first, then the converter products. */
export function cropProducts(crop: CropPlanCrop | undefined): string[] {
  if (!crop) return [];
  const all = [crop.fillType, ...crop.products].filter((x): x is string => !!x);
  return [...new Set(all)];
}

/**
 * Roadmap V4 section A: tab "Anbauplanung" of the app "Feldbuch" - per field the plan for the next season (crop,
 * product, sowing month, note; "Wie Vorjahr", "Fruchtfolge vorschlagen") with the hints and the forecast, the needs of
 * the plan (seed, marketing, feed), plan and reality, the reminder switch and "Plan besprechen".
 */
@Component({
  selector: 'app-crop-planning',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Card, Badge, Button],
  template: `
    @if (failed()) {
      <p class="text-sm text-danger">{{ 'common.error' | t }}</p>
    }
    @if (view(); as v) {
      <div class="flex flex-col gap-4">
        <app-card [title]="'cropPlan.title' | t" data-testid="cp-card">
          <div card-actions class="flex flex-wrap items-center gap-3 text-[12px]">
            <label class="flex items-center gap-1.5 text-muted">
              <input type="checkbox" [checked]="v.reminders" (change)="run(api.setCropPlanReminders($any($event.target).checked))" data-testid="cp-reminders" />
              {{ 'cropPlan.reminders' | t }}
            </label>
            <app-button variant="secondary" [disabled]="busy() || v.discussed || !planned()" (pressed)="run(api.discussCropPlan())" data-testid="cp-discuss">
              {{ (v.discussed ? 'cropPlan.discussed' : 'cropPlan.discuss') | t }}
            </app-button>
          </div>
          @if (!v.windows) {
            <p class="mb-3 text-[12px] text-muted" data-testid="cp-no-windows">{{ 'cropPlan.noWindows' | t }}</p>
          }
          @if (v.planMargin !== null) {
            <p class="mb-3 text-[13px] text-text" data-testid="cp-sum">
              {{ 'cropPlan.sum' | t: { plan: (v.planMargin | money), previous: v.previousMargin === null ? '–' : (v.previousMargin | money) } }}
            </p>
          }
          <div class="flex flex-col gap-3">
            @for (f of v.fields; track f.farmlandId) {
              <div class="rounded-xl border border-border p-3" data-testid="cp-field" [attr.data-field]="f.farmlandId">
                <div class="flex flex-wrap items-baseline justify-between gap-2">
                  <span class="font-semibold text-text">{{ 'farmland.fieldLabel' | t: { id: f.name } }} · {{ f.hectares | num: 1 }} ha</span>
                  <span class="text-[12px] text-muted">
                    {{ 'cropPlan.now' | t: { crop: crop(f.runningCrop) } }} · {{ 'cropPlan.last' | t: { crop: crop(f.lastCrop) } }}
                  </span>
                </div>
                <div class="mt-2 flex flex-wrap items-end gap-2">
                  <label class="flex flex-col text-[11px] text-muted">{{ 'cropPlan.crop' | t }}
                    <select class="rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="cp-crop"
                      [value]="draftCrop(f)" (change)="setDraft(f.farmlandId, $any($event.target).value)">
                      <option value="">–</option>
                      <option [value]="fallow">{{ 'cropPlan.fallow' | t }}</option>
                      @for (c of v.crops; track c.name) { <option [value]="c.name">{{ c.title ?? (c.name | label: 'fruitType') }}</option> }
                    </select>
                  </label>
                  @if (productsOf(f).length > 1) {
                    <label class="flex flex-col text-[11px] text-muted">{{ 'cropPlan.product' | t }}
                      <select class="rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="cp-product">
                        @for (p of productsOf(f); track p) { <option [value]="p" [selected]="p === f.plan?.fillType">{{ p | label: 'fillType' }}</option> }
                      </select>
                    </label>
                  }
                  @if (draftCrop(f) && draftCrop(f) !== fallow) {
                    <label class="flex flex-col text-[11px] text-muted">{{ 'cropPlan.sowMonth' | t }}
                      <select class="rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="cp-sow">
                        <option value="">{{ 'cropPlan.window' | t }}</option>
                        @for (m of monthsOf(f); track m) { <option [value]="m" [selected]="m === f.plan?.sowPeriod">{{ ('enums.period.' + m) | t }}</option> }
                      </select>
                    </label>
                  }
                  <label class="flex flex-col text-[11px] text-muted">{{ 'cropPlan.note' | t }}
                    <input #note type="text" maxlength="200" [value]="f.plan?.note ?? ''" class="w-40 rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="cp-note" />
                  </label>
                  <app-button [disabled]="busy() || !draftCrop(f)" (pressed)="save(f, readSelect('cp-product', f), readSelect('cp-sow', f), note.value)" data-testid="cp-save">{{ 'common.save' | t }}</app-button>
                  @if (f.lastCrop || f.runningCrop) {
                    <app-button variant="secondary" [disabled]="busy()" (pressed)="setDraft(f.farmlandId, f.runningCrop ?? f.lastCrop ?? '')" data-testid="cp-same">{{ 'cropPlan.same' | t }}</app-button>
                  }
                  <app-button variant="secondary" [disabled]="busy()" (pressed)="suggest(f)" data-testid="cp-suggest">{{ 'cropPlan.suggest' | t }}</app-button>
                  @if (f.plan) {
                    <app-button variant="danger" [disabled]="busy()" (pressed)="run(api.removeCropPlan(f.farmlandId))" data-testid="cp-remove">{{ 'cropPlan.remove' | t }}</app-button>
                  }
                </div>
                @if (hint()[f.farmlandId]; as h) { <p class="mt-1 text-[12px] text-accent" data-testid="cp-suggestion">{{ h }}</p> }
                @if (f.plan; as p) {
                  <div class="mt-2 text-[12px] text-[#B9C8C2]" data-testid="cp-plan">
                    <app-badge>{{ ('cropPlan.target.' + p.target) | t }}</app-badge>
                    @if (p.forecast.margin !== null) {
                      <span class="ml-2" data-testid="cp-forecast">{{ 'cropPlan.forecast' | t: {
                        lph: (p.forecast.litersPerHa | num), source: (('enums.cropYieldSource.' + p.forecast.yieldSource) | t),
                        price: p.forecast.pricePerLiter === null ? '–' : ((p.forecast.pricePerLiter * 1000) | money),
                        priceSource: (('enums.cropPriceSource.' + p.forecast.priceSource) | t), margin: (p.forecast.margin | money) } }}</span>
                    } @else if (p.fruitType !== fallow) {
                      <span class="ml-2 text-muted">{{ 'cropPlan.noForecast' | t }}</span>
                    }
                    @if (p.forecast.costSource === 'SEED') { <span class="ml-1 text-muted">· {{ 'cropPlan.seedOnly' | t }}</span> }
                    @if (p.window; as w) {
                      <div class="mt-1" data-testid="cp-window">{{ (w.active ? 'cropPlan.windowNow' : 'cropPlan.windowNext') | t: { start: (w.start | gameTime), end: (w.end | gameTime) } }}
                        <a routerLink="/farmland/karte" class="ml-2 text-accent hover:underline" data-testid="cp-contractor">{{ 'cropPlan.contractor' | t }} →</a>
                      </div>
                    }
                    @for (c of p.checks; track c.code) {
                      <div class="mt-1 text-warn" data-testid="cp-check">⚠ {{ checkText(c) }}</div>
                    }
                  </div>
                }
              </div>
            } @empty {
              <p class="text-sm text-muted" data-testid="cp-no-fields">{{ 'cropPlan.noFields' | t }}</p>
            }
          </div>
        </app-card>

        @if (v.seeds.length || v.marketing.length || v.feed.length) {
          <app-card [title]="'cropPlan.needs' | t" data-testid="cp-needs">
            <div class="grid grid-cols-1 gap-4 md:grid-cols-3 text-[13px]">
              <div data-testid="cp-seeds">
                <div class="mb-1 font-semibold text-text">{{ 'cropPlan.seed' | t }}</div>
                @for (s of v.seeds; track s.fruitType) {
                  <div>{{ crop(s.fruitType) }}: {{ s.liters === null ? '–' : (s.liters | num) + ' l' }} · {{ s.cost === null ? '–' : (s.cost | money) }}</div>
                }
                <div class="mt-1 text-[11px] text-muted">{{ 'cropPlan.seedHint' | t }}</div>
              </div>
              <div data-testid="cp-marketing">
                <div class="mb-1 font-semibold text-text">{{ 'cropPlan.marketing' | t }}</div>
                @for (m of v.marketing; track m.fillType) {
                  <div>{{ m.fillType | label: 'fillType' }}: {{ 'cropPlan.sold' | t: { percent: (m.soldPercent ?? 0 | num), liters: (m.expected | num) } }}</div>
                }
                <a routerLink="/market/vorkontrakte" class="text-[12px] text-accent hover:underline" data-testid="cp-forward">{{ 'cropPlan.forward' | t }} →</a>
              </div>
              <div data-testid="cp-feed">
                <div class="mb-1 font-semibold text-text">{{ 'cropPlan.feed' | t }}</div>
                @for (n of v.feed; track n.animalType + n.title) {
                  <div>{{ n.kind === 'BEDDING' ? ('feed.bedding' | t) : n.title }} ({{ n.animalType | label: 'animalType' }}): {{ 'cropPlan.covered' | t: { percent: (n.coveredPercent ?? 0 | num) } }}
                    @if (n.suggestCrop && n.suggestHectares) {
                      <span class="text-[11px] text-muted">· {{ 'cropPlan.feedSuggest' | t: { hectares: (n.suggestHectares | num: 1), crop: crop(n.suggestCrop) } }}</span>
                    }
                  </div>
                } @empty { <div class="text-muted">–</div> }
              </div>
            </div>
          </app-card>
        }

        @if (v.history.length) {
          <app-card [title]="'cropPlan.history' | t" data-testid="cp-history">
            <table class="w-full text-[13px]">
              <thead><tr class="text-left text-[11px] uppercase tracking-[0.12em] text-muted">
                <th class="py-1 pr-3">{{ 'cropPlan.field' | t }}</th><th class="py-1 pr-3">{{ 'cropPlan.planned' | t }}</th>
                <th class="py-1 pr-3">{{ 'cropPlan.actual' | t }}</th><th class="py-1 pr-3 text-right">l/ha</th>
                <th class="py-1 text-right">{{ 'cropPlan.margin' | t }}</th></tr></thead>
              <tbody>
                @for (h of v.history; track h.id) {
                  <tr class="border-t border-border" data-testid="cp-actual">
                    <td class="py-1.5 pr-3">{{ h.name }}</td>
                    <td class="py-1.5 pr-3">{{ crop(h.plannedCrop) }}</td>
                    <td class="py-1.5 pr-3">{{ crop(h.actualCrop) }}
                      @if (h.status === 'DEVIATED') { <app-badge variant="warning">{{ 'cropPlan.deviated' | t }}</app-badge> }
                    </td>
                    <td class="py-1.5 pr-3 text-right font-mono">{{ h.plannedLitersPerHa | num }} / {{ h.ended ? (h.actualLitersPerHa | num) : '…' }}</td>
                    <td class="py-1.5 text-right font-mono">{{ h.plannedMargin === null ? '–' : (h.plannedMargin | money) }} / {{ h.ended && h.actualMargin !== null ? (h.actualMargin | money) : '…' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </app-card>
        }
        @if (error(); as err) { <p class="text-[12px] text-danger" data-testid="cp-error">{{ err }}</p> }
      </div>
    }
  `,
})
export class CropPlanning {
  readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly fallow = FALLOW;
  readonly view = signal<CropPlanningView | null>(null);
  readonly failed = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  /** Crop chosen in the form per field (until saved). */
  readonly drafts = signal<Record<number, string>>({});
  /** Text of "Fruchtfolge vorschlagen" per field. */
  readonly hint = signal<Record<number, string>>({});
  readonly planned = computed(() => (this.view()?.fields ?? []).some((f) => f.plan !== null));

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.cropPlanning().subscribe({
      next: (v) => {
        this.view.set(v);
        this.failed.set(false);
      },
      error: () => this.failed.set(true),
    });
  }

  draftCrop(f: CropPlanField): string {
    return this.drafts()[f.farmlandId] ?? f.plan?.fruitType ?? '';
  }

  setDraft(farmlandId: number, crop: string): void {
    this.drafts.update((d) => ({ ...d, [farmlandId]: crop }));
  }

  private cropOf(name: string | null): CropPlanCrop | undefined {
    return this.view()?.crops.find((c) => c.name === name);
  }

  productsOf(f: CropPlanField): string[] {
    return cropProducts(this.cropOf(this.draftCrop(f)));
  }

  monthsOf(f: CropPlanField): number[] {
    return sowMonths(this.cropOf(this.draftCrop(f)));
  }

  crop(name: string | null): string {
    if (!name) return '–';
    if (name === FALLOW) return this.i18n.t('cropPlan.fallow');
    const c = this.cropOf(name);
    if (c?.title) return c.title;
    const key = 'enums.fruitType.' + name;
    return this.i18n.has(key) ? this.i18n.t(key) : name;
  }

  checkText(c: CropPlanCheck): string {
    const v = { ...c.values } as Record<string, unknown>;
    if (Array.isArray(v['periods'])) v['periods'] = (v['periods'] as number[]).map((p) => this.i18n.t('enums.period.' + p)).join(', ');
    if (typeof v['period'] === 'number') v['period'] = this.i18n.t('enums.period.' + v['period']);
    if (typeof v['crop'] === 'string') v['crop'] = this.crop(v['crop'] as string);
    if (typeof v['fillType'] === 'string') {
      const key = 'enums.fillType.' + v['fillType'];
      v['fillType'] = this.i18n.has(key) ? this.i18n.t(key) : v['fillType'];
    }
    if (typeof v['hectares'] === 'number') v['hectares'] = (v['hectares'] as number).toLocaleString('de-DE');
    if (typeof v['premium'] === 'number') v['premium'] = (v['premium'] as number).toLocaleString('de-DE');
    return this.i18n.t('cropPlan.check.' + c.code, v as Record<string, string | number>);
  }

  /** Value of a select of a field row (product / sowing month), null when the row has none or nothing is chosen. */
  readSelect(testId: string, f: CropPlanField): string | null {
    const el = this.host.nativeElement.querySelector(`[data-field="${f.farmlandId}"] [data-testid="${testId}"]`) as HTMLSelectElement | null;
    return el && el.value !== '' ? el.value : null;
  }

  save(f: CropPlanField, product: string | null, sow: string | null, note: string): void {
    const fruitType = this.draftCrop(f);
    const fallow = fruitType === FALLOW;
    this.run(this.api.saveCropPlan(f.farmlandId, {
      fruitType,
      fillType: fallow ? null : product,
      sowPeriod: fallow || !sow ? null : Number(sow),
      note: note.trim() || null,
    }), () => this.drafts.update((d) => {
      const next = { ...d };
      delete next[f.farmlandId];
      return next;
    }));
  }

  suggest(f: CropPlanField): void {
    this.busy.set(true);
    this.api.cropSuggestion(f.farmlandId).subscribe({
      next: (s) => {
        this.busy.set(false);
        if (s) {
          this.setDraft(f.farmlandId, s.fruitType);
          this.hint.update((h) => ({ ...h, [f.farmlandId]: this.i18n.t('cropPlan.suggestion.' + s.reason, { crop: this.crop(s.fruitType) }) }));
        } else {
          this.hint.update((h) => ({ ...h, [f.farmlandId]: this.i18n.t('cropPlan.suggestion.NONE') }));
        }
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  run(o: Observable<CropPlanningView>, after?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    o.subscribe({
      next: (v) => {
        this.busy.set(false);
        this.view.set(v);
        after?.();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
