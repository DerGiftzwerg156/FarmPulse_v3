import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { DiseaseStatusView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3.1 R31-B4: animal diseases in the app "Ämter" - the active restricted zone (trade of the animal types
 * blocked) and the past ones. The requirements per stable show up as inspections.
 */
@Component({
  selector: 'app-animal-disease-card',
  imports: [TranslatePipe, LabelPipe, GameTimePipe, Card],
  template: `
    <app-card [title]="'authorities.disease.title' | t" data-testid="animal-disease">
      @if (status(); as s) {
        @if (active(); as d) {
          <p class="text-[12px] text-danger" data-testid="disease-active">{{ 'authorities.disease.active' | t: { disease: (d.diseaseKey | label: 'disease'), types: types(d.animalTypes), time: (d.endsGameTime | gameTime) } }}</p>
          <p class="mt-1 text-[11px] text-muted">{{ 'authorities.disease.activeHint' | t }}</p>
        } @else {
          <p class="text-[12px] text-muted" data-testid="disease-none">{{ (s.possible ? 'authorities.disease.none' : 'authorities.disease.off') | t }}</p>
        }
        @if (past().length) {
          <ul class="mt-2 space-y-1 text-[12px]">
            @for (d of past(); track d.id) {
              <li class="text-muted" data-testid="disease-past">{{ 'authorities.disease.past' | t: { disease: (d.diseaseKey | label: 'disease'), types: types(d.animalTypes), from: (d.declaredGameTime | gameTime), to: (d.liftedGameTime | gameTime) } }}</li>
            }
          </ul>
        }
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
    </app-card>
  `,
})
export class AnimalDiseaseCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly labels = new LabelPipe();

  readonly status = signal<DiseaseStatusView | null>(null);
  readonly active = computed(() => this.status()?.diseases.find((d) => d.status === 'ACTIVE') ?? null);
  readonly past = computed(() => (this.status()?.diseases ?? []).filter((d) => d.status !== 'ACTIVE'));

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.animalDiseases().subscribe({
      next: (s) => this.status.set(s),
      error: () => this.status.set({ possible: false, diseases: [] }),
    });
  }

  types(types: string[]): string {
    return types.map((t) => this.labels.transform(t, 'animalType')).join(', ');
  }
}
