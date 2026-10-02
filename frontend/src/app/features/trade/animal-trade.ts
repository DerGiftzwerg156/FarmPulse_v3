import { Component, effect, inject, output, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { AnimalNeighborView, AnimalTradeView, StableView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { TrustMeter } from '../village/trust-meter';

/** BUY = the player asks the neighbour for animals, SELL = the player offers animals to him. */
export type AnimalDirection = 'BUY' | 'SELL';

export interface AnimalDraft {
  direction: AnimalDirection;
  husbandryUniqueId: string;
  subType: string;
  count: number;
}

/** Own stables of an animal type the neighbour keeps. */
export function stablesFor(n: AnimalNeighborView, t: AnimalTradeView): StableView[] {
  const types = n.animals.map((a) => a.type);
  return t.stables.filter((s) => types.includes(s.type));
}

/** Subtypes of a stable: BUY = the ones it can hold, SELL = the ones in it. */
export function subTypesFor(s: StableView, direction: AnimalDirection): string[] {
  return direction === 'BUY' ? s.supportedSubTypes : s.subTypes.filter((x) => x.count > 0).map((x) => x.name);
}

/** Highest count of a deal: the deal size, the neighbour's stock / free places (BUY) or the own animals (SELL). */
export function maxCount(n: AnimalNeighborView, t: AnimalTradeView, s: StableView, d: AnimalDirection, subType: string): number {
  if (d === 'BUY') {
    const stock = n.animals.find((a) => a.type === s.type)?.count ?? 0;
    return Math.max(0, Math.min(t.countMax, stock, s.freeSlots ?? t.countMax));
  }
  const own = s.subTypes.find((x) => x.name === subType)?.count ?? 0;
  return Math.max(0, Math.min(t.countMax, own));
}

/**
 * Roadmap V3.1 R31-A3 in the app "Handel": the neighbours' animals with their prices per animal, "Tiere anfragen"
 * (buy into an own stable) and "Tiere anbieten" (sell from an own stable). The neighbour answers at once with an
 * offer or a request (listed below with the goods offers); the animals move in the game after the acceptance.
 */
@Component({
  selector: 'app-animal-trade',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, Button, Card, TrustMeter],
  template: `
    <app-card [title]="'trade.animals.title' | t" data-testid="animal-trade">
      <p class="mb-3 text-[12px] text-muted">{{ 'trade.animals.intro' | t }}</p>
      @if (view(); as t) {
        @if (!t.tracked) {
          <p class="rounded-sm border border-warn/40 px-3 py-2 text-[12px] text-warn" data-testid="no-stables">{{ 'trade.animals.noStables' | t }}</p>
        } @else {
          <ul class="space-y-3">
            @for (n of withAnimals(t); track n.id) {
              <li class="rounded-xl border border-border bg-bg px-3 py-2" data-testid="animal-neighbor" [attr.data-neighbor]="n.id">
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <span class="font-display text-[12px] font-bold text-text">{{ n.name }}</span>
                  <app-trust-meter [level]="n.trustLevel" />
                </div>
                <ul class="mt-1 space-y-0.5 text-[12px] text-text" data-testid="animal-stock">
                  @for (a of n.animals; track a.type) {
                    <li>{{ 'trade.animals.stock' | t: { count: a.count, type: (a.type | label: 'animalType'), sell: (a.sellUnitPrice | money), buy: (a.buyUnitPrice | money) } }}</li>
                  }
                </ul>
                @if (stables(n, t).length) {
                  @if (draft(n, t); as d) {
                    <form class="mt-2 flex flex-wrap items-end gap-2" (submit)="$event.preventDefault(); send(n, d)" data-testid="animal-form">
                      <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'trade.animals.direction' | t }}
                        <select class="fp-input w-32" [value]="d.direction" (change)="update(n, t, { direction: $any($event.target).value })" data-testid="animal-direction">
                          <option value="BUY">{{ 'trade.animals.buy' | t }}</option>
                          <option value="SELL">{{ 'trade.animals.sell' | t }}</option>
                        </select>
                      </label>
                      <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'trade.animals.stable' | t }}
                        <select class="fp-input w-40" [value]="d.husbandryUniqueId" (change)="update(n, t, { husbandryUniqueId: $any($event.target).value })" data-testid="animal-stable">
                          @for (s of stables(n, t); track s.husbandryUniqueId) {
                            <option [value]="s.husbandryUniqueId">{{ s.type | label: 'animalType' }} · {{ 'trade.animals.stableInfo' | t: { count: s.count, free: s.freeSlots ?? '–' } }}</option>
                          }
                        </select>
                      </label>
                      <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'trade.animals.subType' | t }}
                        <select class="fp-input w-40" [value]="d.subType" (change)="update(n, t, { subType: $any($event.target).value })" data-testid="animal-subtype">
                          @for (x of subTypes(n, t, d); track x) { <option [value]="x">{{ x }}</option> }
                        </select>
                      </label>
                      <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'trade.animals.count' | t }}
                        <input class="fp-input w-20" type="number" [min]="t.countMin" [max]="max(n, t, d)" step="1" [value]="d.count"
                          (input)="update(n, t, { count: +$any($event.target).value })" data-testid="animal-count" />
                      </label>
                      <app-button type="submit" variant="secondary" [disabled]="busy() === n.id || !valid(n, t, d)" data-testid="animal-send">
                        {{ (d.direction === 'BUY' ? 'trade.animals.request' : 'trade.animals.offer') | t }}</app-button>
                      <p class="w-full text-[11px] text-muted">{{ 'trade.animals.countHint' | t: { min: t.countMin, max: max(n, t, d) } }}</p>
                    </form>
                  }
                } @else {
                  <p class="mt-1 text-[11px] text-muted" data-testid="no-matching-stable">{{ 'trade.animals.noMatchingStable' | t }}</p>
                }
                @if (info()?.neighbor === n.id) { <p class="mt-2 text-[12px] text-accent" data-testid="animal-info">{{ info()?.text }}</p> }
                @if (actionError()?.neighbor === n.id) { <p class="mt-2 text-[12px] text-danger" data-testid="animal-error">{{ actionError()?.text }}</p> }
              </li>
            } @empty {
              <li class="text-[12px] text-muted" data-testid="no-animal-neighbors">{{ 'trade.animals.noNeighbors' | t }}</li>
            }
          </ul>
        }
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
    </app-card>
  `,
})
export class AnimalTrade {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** Emitted after a request / offer so the page reloads its case list. */
  readonly changed = output<void>();

  readonly view = signal<AnimalTradeView | null>(null);
  readonly drafts = signal<Record<number, Partial<AnimalDraft>>>({});
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
    this.api.animalTrade().subscribe({ next: (v) => this.view.set(v), error: () => this.view.set(null) });
  }

  withAnimals(t: AnimalTradeView): AnimalNeighborView[] {
    return t.neighbors.filter((n) => n.animals.length > 0);
  }

  stables(n: AnimalNeighborView, t: AnimalTradeView): StableView[] {
    return stablesFor(n, t);
  }

  /** The neighbour's draft completed with defaults (first stable, first subtype, smallest deal). */
  draft(n: AnimalNeighborView, t: AnimalTradeView): AnimalDraft | null {
    const d = this.drafts()[n.id] ?? {};
    const direction: AnimalDirection = d.direction ?? 'BUY';
    const stables = stablesFor(n, t);
    const stable = stables.find((s) => s.husbandryUniqueId === d.husbandryUniqueId) ?? stables[0];
    if (!stable) return null;
    const subTypes = subTypesFor(stable, direction);
    const subType = subTypes.includes(d.subType ?? '') ? d.subType! : (subTypes[0] ?? '');
    return { direction, husbandryUniqueId: stable.husbandryUniqueId, subType, count: d.count ?? t.countMin };
  }

  subTypes(n: AnimalNeighborView, t: AnimalTradeView, d: AnimalDraft): string[] {
    const s = t.stables.find((x) => x.husbandryUniqueId === d.husbandryUniqueId);
    return s ? subTypesFor(s, d.direction) : [];
  }

  max(n: AnimalNeighborView, t: AnimalTradeView, d: AnimalDraft): number {
    const s = t.stables.find((x) => x.husbandryUniqueId === d.husbandryUniqueId);
    return s ? maxCount(n, t, s, d.direction, d.subType) : 0;
  }

  valid(n: AnimalNeighborView, t: AnimalTradeView, d: AnimalDraft): boolean {
    return !!d.subType && Number.isInteger(d.count) && d.count >= t.countMin && d.count <= this.max(n, t, d);
  }

  update(n: AnimalNeighborView, t: AnimalTradeView, change: Partial<AnimalDraft>): void {
    const current = this.draft(n, t) ?? {};
    const next: Partial<AnimalDraft> = { ...current, ...change };
    if (change.direction || change.husbandryUniqueId) next.subType = undefined; // pick the first fitting subtype again
    this.drafts.update((all) => ({ ...all, [n.id]: next }));
  }

  send(n: AnimalNeighborView, d: AnimalDraft): void {
    const call = d.direction === 'BUY'
      ? this.api.requestAnimals(n.id, d.husbandryUniqueId, d.subType, d.count)
      : this.api.offerAnimals(n.id, d.husbandryUniqueId, d.subType, d.count);
    this.busy.set(n.id);
    this.info.set(null);
    this.actionError.set(null);
    call.subscribe({
      next: () => {
        this.busy.set(null);
        this.info.set({ neighbor: n.id, text: this.i18n.t('trade.animals.sent', { name: n.name }) });
        this.load();
        this.changed.emit();
      },
      error: (e) => {
        this.busy.set(null);
        this.actionError.set({ neighbor: n.id, text: apiErrorMessage(e, this.i18n.t('common.error')) });
      },
    });
  }
}
