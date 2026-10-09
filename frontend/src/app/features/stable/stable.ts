import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { BarnView, StablesView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Icon } from '../../shared/ui/icon';
import { ServiceCases } from '../contracts/service-cases';

export interface Bar {
  label: string;
  /** 0..100 */
  value: number;
  warn: boolean;
}

/** Bars of a barn: health and productivity (0..100), food, water and the other conditions (0..1). */
export function barnBars(b: BarnView, s: StablesView, labels: { health: string; food: string; water: string; productivity: string }): Bar[] {
  const bars: Bar[] = [];
  if (b.health !== null) bars.push({ label: labels.health, value: b.health, warn: b.health < s.healthWarnBelow });
  if (b.food !== null) bars.push({ label: labels.food, value: b.food * 100, warn: b.food < s.foodWarnBelow });
  if (b.water !== null) bars.push({ label: labels.water, value: b.water * 100, warn: b.water < s.waterWarnBelow });
  if (b.productivity !== null) bars.push({ label: labels.productivity, value: b.productivity, warn: false });
  return bars;
}

/**
 * Hof-Tablet app "Stall": every stable with the values the game reports (health, food, water, productivity and the
 * other conditions), announced animal welfare inspections, the keepers' load, the vet's next routine visit, and the
 * trader offers, vet visits and breeding advice.
 */
@Component({
  selector: 'app-stable',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Icon, ServiceCases],
  templateUrl: './stable.html',
})
export class Stable {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);

  /** Tab of the route `/stall/:tab` (owner decision 2026-10-06): Ställe, Handel & Tierarzt. */
  readonly tab = input<string>('staelle');
  readonly case = input<string>();
  readonly highlightedCase = computed(() => Number(this.case()) || null);
  readonly stables = signal<StablesView | null>(null);
  readonly failed = signal(false);

  readonly inspections = computed(() => (this.stables()?.barns ?? []).filter((b) => b.inspectionDeadline !== null));
  /** Animals one keeper can handle vs. the animals per active keeper. */
  readonly keeperLoad = computed(() => {
    const s = this.stables();
    if (!s || s.animals === 0) return null;
    return { perKeeper: s.keepers ? Math.round(s.animals / s.keepers) : null, limit: s.animalsPerKeeper, over: !s.keepers || s.animals / s.keepers > s.animalsPerKeeper };
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.stables().subscribe({
      next: (s) => {
        this.stables.set(s);
        this.failed.set(false);
      },
      error: () => this.failed.set(true),
    });
  }

  bars(b: BarnView, s: StablesView, labels: { health: string; food: string; water: string; productivity: string }): Bar[] {
    return barnBars(b, s, labels);
  }

  barnWarn(b: BarnView, s: StablesView): boolean {
    return b.inspectionDeadline !== null || barnBars(b, s, { health: '', food: '', water: '', productivity: '' }).some((x) => x.warn);
  }

  /** Conditions other than water (straw, slurry, milk ...) as the game names them. */
  otherConditions(b: BarnView): { title: string; ratio: number }[] {
    return b.conditions.filter((c) => b.water === null || c.ratio !== b.water);
  }
}
