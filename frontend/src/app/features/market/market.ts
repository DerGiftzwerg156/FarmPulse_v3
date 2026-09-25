import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { forkJoin } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { MarketEventView, PriceSeries, PriceView, StorageOverview } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { formatNumber, gameDay } from '../../shared/format/format';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { ChartSeries, ChartWrapper } from '../../shared/ui/chart-wrapper';
import { PageErrorView } from '../../shared/ui/page-error';
import { Stat } from '../../shared/ui/stat';

const DAY = 86_400_000;
export const RANGES = [7, 30, 90, 0] as const; // 0 = whole savegame

/** Price series -> chart series; color slot follows the sell point (stable across filters), never the rank. */
export function toChartSeries(series: PriceSeries[], sellPointOrder: string[]): ChartSeries[] {
  return series.map((s) => ({
    key: `${s.sellPoint}|${s.fillType}`,
    label: s.sellPointName,
    colorIndex: Math.max(0, sellPointOrder.indexOf(s.sellPoint)),
    points: s.points.map((p) => ({ x: p.gameTime, y: p.price })),
  }));
}

/**
 * Storage & market prices (AP-8.7): silo stock per fill type with value, current prices per sell point, price
 * history chart with selectable fill type / sell point / period, announced market events incl. special contracts.
 */
@Component({
  selector: 'app-market',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Card, Badge, Button, Stat, ChartWrapper, PageErrorView],
  templateUrl: './market.html',
})
export class Market {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?event=` highlights a market event (link from a special-offer mail). */
  readonly event = input<string>();

  readonly ranges = RANGES;
  readonly storage = signal<StorageOverview | null>(null);
  readonly prices = signal<PriceView[] | null>(null);
  readonly events = signal<MarketEventView[]>([]);
  readonly history = signal<PriceSeries[] | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly fillType = signal<string | null>(null);
  readonly sellPoint = signal<string>('');
  readonly rangeDays = signal<number>(30);

  readonly fillTypes = computed(() => [...new Set((this.prices() ?? []).map((p) => p.fillType))].sort());
  readonly sellPoints = computed(() => {
    const seen = new Map<string, string>();
    (this.prices() ?? []).forEach((p) => seen.set(p.sellPoint, p.sellPointName));
    return [...seen.entries()].map(([id, name]) => ({ id, name }));
  });
  readonly sellPointOrder = computed(() => this.sellPoints().map((s) => s.id));
  readonly pricesForType = computed(() => {
    const ft = this.fillType();
    const list = (this.prices() ?? []).filter((p) => !ft || p.fillType === ft);
    return [...list].sort((a, b) => a.fillType.localeCompare(b.fillType) || b.currentPrice - a.currentPrice);
  });
  readonly bestPrice = computed(() => {
    const best = new Map<string, number>();
    (this.prices() ?? []).forEach((p) => best.set(p.fillType, Math.max(best.get(p.fillType) ?? 0, p.currentPrice)));
    return best;
  });
  readonly chartSeries = computed(() => toChartSeries(this.history() ?? [], this.sellPointOrder()));
  readonly openEvents = computed(() => this.events().filter((e) => ['OFFERED', 'ACTIVE', 'PLANNED', 'RUMOR_ONLY'].includes(e.status)));
  readonly pastEvents = computed(() => this.events().filter((e) => !['OFFERED', 'ACTIVE', 'PLANNED', 'RUMOR_ONLY'].includes(e.status)));
  readonly highlighted = computed(() => Number(this.event()) || null);

  readonly xFormat = (x: number) => this.i18n.t('common.day', { day: gameDay(x) });
  readonly yFormat = (y: number) => `${formatNumber(y)} €`;

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      untracked(() => this.load());
    });
  }

  load(): void {
    forkJoin({ storage: this.api.storage(), prices: this.api.currentPrices(), events: this.api.marketEvents() }).subscribe({
      next: ({ storage, prices, events }) => {
        this.storage.set(storage);
        this.prices.set(prices);
        this.events.set(events);
        this.error.set(null);
        if (!this.fillType() || !prices.some((p) => p.fillType === this.fillType())) {
          const stocked = [...storage.items].sort((a, b) => b.value - a.value)[0]?.fillType;
          this.fillType.set(stocked && prices.some((p) => p.fillType === stocked) ? stocked : (this.fillTypes()[0] ?? null));
        }
        this.loadHistory();
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  loadHistory(): void {
    const now = this.store.savegame()?.gameTime ?? this.storage()?.gameTime ?? 0;
    const days = this.rangeDays();
    this.api
      .priceHistory({
        fillType: this.fillType() ?? undefined,
        sellPoint: this.sellPoint() || undefined,
        from: days > 0 ? Math.max(0, now - days * DAY) : undefined,
      })
      .subscribe({ next: (h) => this.history.set(h), error: () => this.history.set([]) });
  }

  setFillType(v: string): void {
    this.fillType.set(v);
    this.loadHistory();
  }

  setSellPoint(v: string): void {
    this.sellPoint.set(v);
    this.loadHistory();
  }

  setRange(days: number): void {
    this.rangeDays.set(days);
    this.loadHistory();
  }

  sellPointName(id: string): string {
    return this.sellPoints().find((s) => s.id === id)?.name ?? id;
  }

  fill(amount: number, capacity: number): number {
    return capacity > 0 ? Math.min(100, (amount / capacity) * 100) : 0;
  }

  participate(e: MarketEventView, yes: boolean): void {
    this.actionError.set(null);
    this.api.participate(e.id, yes).subscribe({
      next: (u) => this.events.update((list) => list.map((x) => (x.id === u.id ? u : x))),
      error: (err) => this.actionError.set(apiErrorMessage(err, this.i18n.t('common.error'))),
    });
  }
}
