import { Component, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { DieselTheftView, FuelVehicleView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3.1 R31-D8: tank locks of the workshop - per own vehicle once, it makes a diesel theft at this vehicle much
 * less likely. Below the thefts that happened (litres, damage, payout of the insurance module).
 */
@Component({
  selector: 'app-tank-lock-card',
  imports: [TranslatePipe, MoneyPipe, GameTimePipe, Badge, Button, Card],
  template: `
    <app-card [title]="'workshop.tankLock.title' | t" data-testid="tank-locks">
      @if (view(); as v) {
        <p class="mb-3 text-[12px] text-muted">{{ 'workshop.tankLock.intro' | t: { price: (v.tankLockPrice | money) } }}</p>
        <ul class="space-y-2">
          @for (x of v.vehicles; track x.vehicleId) {
            <li class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border bg-bg px-3 py-2 text-[13px]" data-testid="tank-vehicle">
              <span class="text-text">{{ x.name ?? x.vehicleId }}
                @if (x.fuelLiters !== null) { <span class="fp-label">· {{ 'workshop.tankLock.fuel' | t: { liters: x.fuelLiters, capacity: x.fuelCapacity } }}</span> }</span>
              @if (x.tankLock) {
                <app-badge variant="positive">{{ 'workshop.tankLock.locked' | t }}</app-badge>
              } @else if (x.fuelCapacity !== null) {
                <app-button variant="secondary" [disabled]="busy()" (pressed)="buy(x)" data-testid="tank-lock-buy">{{ 'workshop.tankLock.buy' | t: { price: (v.tankLockPrice | money) } }}</app-button>
              }
            </li>
          } @empty {
            <li class="text-[13px] text-muted">{{ 'workshop.tankLock.none' | t }}</li>
          }
        </ul>
        @if (v.thefts.length > 0) {
          <h3 class="mt-4 fp-label">{{ 'workshop.tankLock.thefts' | t }}</h3>
          <ul class="mt-1 space-y-1 text-[12px]">
            @for (t of v.thefts; track t.id) {
              <li data-testid="theft">{{ 'workshop.tankLock.theft' | t: { time: (t.closedGameTime | gameTime), vehicle: t.vehicleName ?? t.vehicleId, liters: t.stolenLiters, damage: (t.damage | money) } }}
                @if (t.insurancePayout) { · {{ 'workshop.tankLock.paid' | t: { amount: (t.insurancePayout | money) } }} }</li>
            }
          </ul>
        }
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="tank-lock-error">{{ e }}</p> }
    </app-card>
  `,
})
export class TankLockCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);

  readonly view = signal<DieselTheftView | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.dieselTheft().subscribe({
      next: (v) => this.view.set(v),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  buy(x: FuelVehicleView): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.buyTankLock(x.vehicleId).subscribe({
      next: (v) => {
        this.busy.set(false);
        this.view.set(v);
        this.store.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
