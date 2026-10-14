import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { FeedBalanceView, FeedGroup, FeedStatus, FeedStockRequest } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/** Badge of the traffic light (owner decision 2026-10-13: in game months). */
export function feedVariant(status: FeedStatus): BadgeVariant {
  return status === 'GREEN' ? 'positive' : status === 'YELLOW' ? 'warning' : status === 'RED' ? 'negative' : 'neutral';
}

/** Fill types the player can enter stock for: every fill type of a group, plus straw. */
export function feedFillTypes(v: FeedBalanceView): string[] {
  const all = new Set<string>(['STRAW']);
  v.animals.forEach((a) => a.groups.forEach((g) => g.fillTypes.forEach((f) => all.add(f))));
  return [...all].sort();
}

/**
 * Roadmap V4 section F: tab "Futter" of the app "Stall" - per animal type and food group the need per day, the stock
 * (silos, bunker silos, troughs, entered bales), the reach in days and game months with the traffic light, the day it
 * runs empty, the shortfall until the next harvest and the yearly need; per stable the trough; the stock the player
 * enters (bales of a size or litres) with the reminder "bitte prüfen".
 */
@Component({
  selector: 'app-feed-balance',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Card, Badge, Button],
  template: `
    @if (failed()) {
      <p class="text-sm text-danger">{{ 'common.error' | t }}</p>
    }
    @if (view(); as v) {
      @if (!v.tracked) {
        <app-card data-testid="feed-untracked"><p class="text-sm text-muted">{{ 'feed.untracked' | t }}</p></app-card>
      } @else {
        <div class="flex flex-col gap-4">
          @if (!v.counted) {
            <p class="text-[12px] text-muted" data-testid="feed-not-counted">{{ 'feed.notCounted' | t }}</p>
          }
          @for (a of v.animals; track a.animalType) {
            <app-card [title]="(a.animalType | label: 'animalType') + ' · ' + (a.animals | num)" data-testid="feed-animal">
              <div class="overflow-x-auto">
                <table class="w-full min-w-[640px] text-[13px]">
                  <thead>
                    <tr class="text-left text-[11px] uppercase tracking-[0.12em] text-muted">
                      <th class="py-1.5 pr-3">{{ 'feed.group' | t }}</th>
                      <th class="py-1.5 pr-3 text-right">{{ 'feed.needPerDay' | t }}</th>
                      <th class="py-1.5 pr-3 text-right">{{ 'feed.stock' | t }}</th>
                      <th class="py-1.5 pr-3 text-right">{{ 'feed.reach' | t }}</th>
                      <th class="py-1.5 pr-3">{{ 'feed.emptyAt' | t }}</th>
                      <th class="py-1.5 pr-3 text-right">{{ 'feed.year' | t }}</th>
                      <th class="py-1.5 text-right">{{ 'feed.monthlyCost' | t }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (g of a.groups; track g.kind + g.title) {
                      <tr class="border-t border-border align-top" data-testid="feed-group" [attr.data-status]="g.status">
                        <td class="py-2 pr-3">
                          <div class="font-semibold text-text">{{ groupTitle(g) }}</div>
                          <div class="text-[11px] text-muted">{{ fillTypeList(g) }}</div>
                          @if (g.neighborTrade && (g.status === 'RED' || g.status === 'YELLOW')) {
                            <a routerLink="/handel" class="text-[11px] text-accent hover:underline" data-testid="feed-neighbor">{{ 'feed.askNeighbor' | t }} →</a>
                          }
                        </td>
                        <td class="py-2 pr-3 text-right font-mono">
                          {{ g.needPerDay | num }} l
                          @if (g.estimated && g.needPerDay > 0) { <div class="text-[11px] text-muted" data-testid="feed-estimated">{{ 'feed.estimated' | t }}</div> }
                        </td>
                        <td class="py-2 pr-3 text-right font-mono">
                          {{ g.stock + g.trough | num }} l
                          @if (g.manual > 0) { <div class="text-[11px] text-muted">{{ 'feed.ofManual' | t: { liters: (g.manual | num) } }}</div> }
                        </td>
                        <td class="py-2 pr-3 text-right">
                          <app-badge [variant]="variant(g.status)" [dot]="g.status === 'RED'" data-testid="feed-status">{{ ('enums.feedStatus.' + g.status) | t }}</app-badge>
                          @if (g.reachDays !== null) {
                            <div class="mt-1 font-mono" data-testid="feed-reach">{{ 'feed.reachValue' | t: { days: (g.reachDays | num: 1), months: (g.reachMonths | num: 1) } }}</div>
                          }
                        </td>
                        <td class="py-2 pr-3">
                          @if (g.emptyAt !== null) { <span class="font-mono">{{ g.emptyAt | gameTime }}</span> } @else { – }
                          @if (g.nextHarvestAt !== null) {
                            <div class="text-[11px] text-muted" data-testid="feed-harvest">{{ 'feed.nextHarvest' | t: { time: (g.nextHarvestAt | gameTime) } }}@if (g.nextHarvestSource === 'PLAN') { {{ 'feed.fromPlan' | t }} }</div>
                            @if (g.shortfall) { <div class="text-[11px] text-danger" data-testid="feed-shortfall">{{ 'feed.shortfall' | t: { liters: (g.shortfall | num) } }}</div> }
                          }
                        </td>
                        <td class="py-2 pr-3 text-right font-mono">
                          @if (g.coveredPercent !== null) {
                            <span [class.text-danger]="g.yearBalance < 0">{{ 'feed.covered' | t: { percent: (g.coveredPercent | num) } }}</span>
                            <div class="text-[11px] text-muted">{{ 'feed.yearNeed' | t: { liters: (g.yearNeed | num) } }}</div>
                            @if (g.plannedHarvest !== null && g.coveredWithPlanPercent !== null) {
                              <!-- R4-F5: with the planned harvest of the crop plan (A4) -->
                              <div class="text-[11px]" [class.text-danger]="(g.yearBalanceWithPlan ?? 0) < 0" data-testid="feed-with-plan">
                                {{ 'feed.withPlan' | t: { percent: (g.coveredWithPlanPercent | num), liters: (g.plannedHarvest | num) } }}
                                · {{ ((g.yearBalanceWithPlan ?? 0) < 0 ? 'feed.planShort' : 'feed.planSurplus') | t: { liters: (abs(g.yearBalanceWithPlan) | num) } }}
                              </div>
                            }
                          } @else { – }
                        </td>
                        <td class="py-2 text-right font-mono">{{ g.monthlyCost === null ? '–' : (g.monthlyCost | money) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </app-card>
          }

          <app-card [title]="'feed.stables' | t" data-testid="feed-stables">
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
              @for (s of v.stables; track s.husbandryUniqueId) {
                <div class="rounded-xl border border-border p-3" data-testid="feed-stable">
                  <div class="flex items-baseline justify-between">
                    <span class="font-semibold text-text">{{ s.animalType | label: 'animalType' }} · {{ s.count | num }}</span>
                    <app-badge [variant]="s.measured ? 'positive' : 'neutral'">{{ (s.measured ? 'feed.measured' : 'feed.estimated') | t }}</app-badge>
                  </div>
                  <div class="mt-1 text-[12px] text-[#B9C8C2]">{{ 'feed.stableNeed' | t: { liters: (s.needPerDay | num) } }}</div>
                  @if (s.troughLiters !== null) {
                    <div class="text-[12px] text-[#B9C8C2]" data-testid="feed-trough">{{ 'feed.trough' | t: { liters: (s.troughLiters | num), capacity: (s.troughCapacity | num) } }}
                      @if (s.troughHours !== null) { · {{ 'feed.troughHours' | t: { hours: (s.troughHours | num) } }} }
                    </div>
                  }
                  @if (s.strawPerDay !== null) {
                    <div class="text-[12px] text-[#B9C8C2]">{{ 'feed.straw' | t: { liters: (s.straw | num), perDay: (s.strawPerDay | num) } }}</div>
                  }
                  @if (s.monthlyCost !== null) {
                    <div class="text-[12px] text-muted">{{ 'feed.stableCost' | t: { amount: (s.monthlyCost | money) } }}</div>
                  }
                </div>
              }
            </div>
            @if (v.monthlyCost !== null) {
              <p class="mt-3 text-[13px] text-text" data-testid="feed-total-cost">{{ 'feed.totalCost' | t: { amount: (v.monthlyCost | money) } }}</p>
            }
          </app-card>

          <app-card [title]="'feed.manual' | t" data-testid="feed-manual">
            <p class="mb-3 text-[12px] text-muted">{{ 'feed.manualHint' | t }}</p>
            @for (m of v.manual; track m.id) {
              <div class="mb-2 flex flex-wrap items-center gap-2 rounded-xl border p-2.5 text-[13px]" [class.border-warn/50]="m.stale" [class.border-border]="!m.stale" data-testid="feed-manual-entry">
                <span class="font-semibold text-text">{{ m.fillType | label: 'fillType' }}</span>
                <span class="font-mono">{{ m.liters | num }} l</span>
                @if (m.balesLeft !== null) { <span class="text-muted">{{ 'feed.balesLeft' | t: { bales: (m.balesLeft | num: 1), size: (('enums.baleSize.' + m.baleSize) | t) } }}</span> }
                @if (m.note) { <span class="text-muted">· {{ m.note }}</span> }
                <span class="text-[11px] text-muted">{{ 'feed.age' | t: { days: (m.ageDays | num) } }}</span>
                @if (m.stale) { <app-badge variant="warning" data-testid="feed-stale">{{ 'feed.stale' | t }}</app-badge> }
                <span class="ml-auto flex gap-1.5">
                  <app-button variant="secondary" [disabled]="busy()" (pressed)="run(api.checkFeedStock(m.id))" data-testid="feed-check">{{ 'feed.check' | t }}</app-button>
                  <app-button variant="danger" [disabled]="busy()" (pressed)="run(api.removeFeedStock(m.id))" data-testid="feed-remove">{{ 'feed.remove' | t }}</app-button>
                </span>
              </div>
            }
            <div class="mt-3 flex flex-wrap items-end gap-2" data-testid="feed-add">
              <label class="flex flex-col text-[11px] text-muted">{{ 'feed.fillType' | t }}
                <select #ft class="rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="feed-add-filltype">
                  @for (f of fillTypes(); track f) { <option [value]="f">{{ f | label: 'fillType' }}</option> }
                </select>
              </label>
              <label class="flex flex-col text-[11px] text-muted">{{ 'feed.bales' | t }}
                <input #bales type="number" min="0" class="w-20 rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="feed-add-bales" />
              </label>
              <label class="flex flex-col text-[11px] text-muted">{{ 'feed.baleSize' | t }}
                <select #size class="rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="feed-add-size">
                  @for (s of baleSizes(); track s.key) { <option [value]="s.key">{{ ('enums.baleSize.' + s.key) | t }} ({{ s.liters | num }} l)</option> }
                </select>
              </label>
              <label class="flex flex-col text-[11px] text-muted">{{ 'feed.orLiters' | t }}
                <input #liters type="number" min="0" class="w-28 rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="feed-add-liters" />
              </label>
              <label class="flex flex-col text-[11px] text-muted">{{ 'feed.note' | t }}
                <input #note type="text" maxlength="200" class="w-40 rounded-md border border-border bg-bg px-2 py-1.5 text-[13px] text-text" data-testid="feed-add-note" />
              </label>
              <app-button [disabled]="busy()" (pressed)="add(ft.value, bales.value, size.value, liters.value, note.value)" data-testid="feed-add-save">{{ 'feed.add' | t }}</app-button>
            </div>
            @if (error(); as err) { <p class="mt-2 text-[12px] text-danger" data-testid="feed-error">{{ err }}</p> }
          </app-card>
        </div>
      }
    }
  `,
})
export class FeedBalance {
  readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly view = signal<FeedBalanceView | null>(null);
  readonly failed = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly fillTypes = computed(() => {
    const v = this.view();
    return v ? feedFillTypes(v) : [];
  });
  readonly baleSizes = computed(() => Object.entries(this.view()?.baleSizes ?? {}).map(([key, liters]) => ({ key, liters })));

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.feedBalance().subscribe({
      next: (v) => {
        this.view.set(v);
        this.failed.set(false);
      },
      error: () => this.failed.set(true),
    });
  }

  abs(v: number | null): number {
    return Math.abs(v ?? 0);
  }

  variant(status: FeedStatus): BadgeVariant {
    return feedVariant(status);
  }

  groupTitle(g: FeedGroup): string {
    return g.kind === 'BEDDING' ? this.i18n.t('feed.bedding') : g.title;
  }

  fillTypeList(g: FeedGroup): string {
    return g.fillTypes.map((f) => {
      const key = 'enums.fillType.' + f;
      return this.i18n.has(key) ? this.i18n.t(key) : f;
    }).join(', ');
  }

  /** Bales of the chosen size when a number of bales is given, otherwise litres. */
  add(fillType: string, bales: string, size: string, liters: string, note: string): void {
    const r: FeedStockRequest = bales.trim() !== ''
      ? { fillType, bales: Number(bales), baleSize: size, liters: null, note: note.trim() || null }
      : { fillType, bales: null, baleSize: null, liters: liters.trim() === '' ? null : Number(liters), note: note.trim() || null };
    this.run(this.api.addFeedStock(r));
  }

  run(o: Observable<FeedBalanceView>): void {
    this.busy.set(true);
    this.error.set(null);
    o.subscribe({
      next: (v) => {
        this.busy.set(false);
        this.view.set(v);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
