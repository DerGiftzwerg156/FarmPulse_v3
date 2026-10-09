import { Component, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { TradeNeighborView, TradeView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';
import { ServiceCases } from '../contracts/service-cases';
import { TrustMeter } from '../village/trust-meter';
import { AnimalTrade } from './animal-trade';
import { BulkOrdersCard } from './bulk-orders-card';
import { CoopSharesCard } from './coop-shares-card';
import { FarmHolidayCard } from './farm-holiday-card';

/** One fill type the player can ask a neighbour for: his stock with a price and room in an own silo. */
export interface Requestable {
  fillType: string;
  /** Litres: min(stock, free capacity of the own silos). */
  max: number;
}

/** R3-H3: goods of a neighbour the player can buy (price known, own silo with free capacity). */
export function requestables(n: TradeNeighborView, t: TradeView): Requestable[] {
  return n.stock
    .filter((s) => s.unitPrice !== null && s.amount > 0)
    .map((s) => ({ fillType: s.fillType, max: Math.min(s.amount, t.silos.find((x) => x.fillType === s.fillType)?.freeCapacity ?? 0) }))
    .filter((r) => r.max > 0);
}

/**
 * Hof-Tablet app "Handel" (Roadmap V3 R3-H, owner decision): the own silo goods, the neighbours with role, stock and
 * needs, "Ware anfragen" (H3), "Nach Arbeit fragen" (H5) and the offers, requests and contracts of the neighbours.
 * Tabs (owner decision 2026-10-06): Nachbarn & Ware, Tierhandel, Hofladen, Großaufträge (Roadmap V3.2 R32-G),
 * Ferienwohnung, Genossenschaft.
 */
@Component({
  selector: 'app-trade',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, Card, Button, TrustMeter, PageErrorView, ServiceCases, AnimalTrade,
    FarmHolidayCard, CoopSharesCard, BulkOrdersCard],
  templateUrl: './trade.html',
})
export class Trade {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);
  readonly casesView = viewChild(ServiceCases);
  /** Tab of the route `/handel/:tab`. */
  readonly tab = input<string>('nachbarn');

  /** `?neighbor=` highlights a neighbour (link from "Kontakte"). */
  readonly neighbor = input<string>();
  readonly highlightedNeighbor = computed(() => Number(this.neighbor()) || null);
  /** `?case=` highlights an offer, request or contract (links from mails, "Aufgaben", in-game questions). */
  readonly case = input<string>();
  readonly highlightedCase = computed(() => Number(this.case()) || null);

  readonly trade = signal<TradeView | null>(null);
  readonly error = signal<PageError | null>(null);
  /** Per neighbour: chosen fill type and litres of "Ware anfragen". */
  readonly drafts = signal<Record<number, { fillType: string; amount: number }>>({});
  readonly busy = signal<number | null>(null);
  readonly info = signal<{ neighbor: number; text: string } | null>(null);
  readonly actionError = signal<{ neighbor: number; text: string } | null>(null);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.trade().subscribe({
      next: (t) => {
        this.trade.set(t);
        this.error.set(null);
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  requestables(n: TradeNeighborView, t: TradeView): Requestable[] {
    return requestables(n, t);
  }

  /** The draft of a neighbour, falling back to his first requestable fill type and its maximum. */
  draft(n: TradeNeighborView, t: TradeView): { fillType: string; amount: number; max: number } | null {
    const options = requestables(n, t);
    if (!options.length) return null;
    const d = this.drafts()[n.id];
    const chosen = options.find((o) => o.fillType === d?.fillType) ?? options[0];
    const amount = d?.fillType === chosen.fillType ? d.amount : chosen.max;
    return { fillType: chosen.fillType, amount, max: chosen.max };
  }

  setFillType(n: TradeNeighborView, t: TradeView, fillType: string): void {
    const max = requestables(n, t).find((o) => o.fillType === fillType)?.max ?? 0;
    this.drafts.update((d) => ({ ...d, [n.id]: { fillType, amount: max } }));
  }

  setAmount(n: TradeNeighborView, fillType: string, amount: number): void {
    this.drafts.update((d) => ({ ...d, [n.id]: { fillType, amount } }));
  }

  canAskForWork(n: TradeNeighborView, t: TradeView): boolean {
    return t.fieldsTracked && t.missionLimitReached !== true && n.farmlands.length > 0;
  }

  request(n: TradeNeighborView, t: TradeView): void {
    const d = this.draft(n, t);
    if (!d || !(d.amount > 0)) return;
    this.run(n, this.api.requestGoods(n.id, d.fillType, Math.round(d.amount)), 'trade.requested');
  }

  askForWork(n: TradeNeighborView): void {
    this.run(n, this.api.askForWork(n.id), 'trade.workOffered');
  }

  private run(n: TradeNeighborView, call: ReturnType<ApiService['askForWork']>, infoKey: string): void {
    this.busy.set(n.id);
    this.info.set(null);
    this.actionError.set(null);
    call.subscribe({
      next: () => {
        this.busy.set(null);
        this.info.set({ neighbor: n.id, text: this.i18n.t(infoKey, { name: n.name }) });
        this.drafts.update((d) => {
          const rest = { ...d };
          delete rest[n.id];
          return rest;
        });
        this.load();
        this.casesView()?.load();
      },
      error: (e) => {
        this.busy.set(null);
        this.actionError.set({ neighbor: n.id, text: apiErrorMessage(e, this.i18n.t('common.error')) });
      },
    });
  }
}
