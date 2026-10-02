import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Observable } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { PriceAlarmsView, PriceView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3 R3-M1: price alarms of the Agrarbörse - fill type, sell point (or any = best price), threshold per
 * 1,000 l and direction. A fired alarm shows a hint in the game and a mail of the land agent; it can be re-activated.
 */
@Component({
  selector: 'app-price-alarm-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Badge, Button, Card],
  template: `
    <app-card [title]="'market.alarm.title' | t" data-testid="price-alarms">
      <form class="flex flex-wrap items-end gap-2" (submit)="$event.preventDefault(); create()" data-testid="alarm-form">
        <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'market.alarm.fillType' | t }}
          <select class="fp-input w-36" [value]="fillType()" (change)="fillType.set($any($event.target).value)" data-testid="alarm-filltype">
            @for (f of fillTypes(); track f) { <option [value]="f">{{ f | label: 'fillType' }}</option> }
          </select>
        </label>
        <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'market.alarm.sellPoint' | t }}
          <select class="fp-input w-40" [value]="sellPoint()" (change)="sellPoint.set($any($event.target).value)" data-testid="alarm-sellpoint">
            <option value="">{{ 'market.alarm.any' | t }}</option>
            @for (s of sellPoints(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
          </select>
        </label>
        <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'market.alarm.direction' | t }}
          <select class="fp-input w-28" [value]="direction()" (change)="direction.set($any($event.target).value)" data-testid="alarm-direction">
            <option value="ABOVE">{{ 'market.alarm.ABOVE' | t }}</option>
            <option value="BELOW">{{ 'market.alarm.BELOW' | t }}</option>
          </select>
        </label>
        <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'market.alarm.threshold' | t }}
          <input class="fp-input w-28 font-mono" type="number" min="1" step="1" [value]="threshold() ?? ''"
            (input)="setThreshold($any($event.target).value)" data-testid="alarm-threshold" />
        </label>
        <app-button type="submit" [disabled]="busy() || !fillType() || !(threshold() ?? 0)" data-testid="alarm-create">{{ 'market.alarm.create' | t }}</app-button>
      </form>
      @if (error()) {
        <p class="mt-2 text-[12px] text-danger" data-testid="alarm-error">{{ error() }}</p>
      }
      <ul class="mt-3 space-y-1.5">
        @for (a of data()?.alarms ?? []; track a.id) {
          <li class="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-bg px-2.5 py-1.5 text-[12px]" data-testid="alarm">
            <span class="text-text">{{ a.fillType | label: 'fillType' }} · {{ a.sellPoint ? sellPointName(a.sellPoint) : ('market.alarm.any' | t) }}
              · {{ ('market.alarm.' + a.direction) | t }} {{ a.threshold | money }}</span>
            <span class="flex items-center gap-1.5">
              @if (a.status === 'FIRED') {
                <app-badge variant="positive">{{ 'market.alarm.fired' | t: { price: (a.firedPrice | money), at: (a.firedGameTime | gameTime) } }}</app-badge>
                <app-button variant="secondary" [disabled]="busy()" (pressed)="reactivate(a.id)" data-testid="alarm-reactivate">{{ 'market.alarm.reactivate' | t }}</app-button>
              } @else {
                <app-badge>{{ 'market.alarm.active' | t }}</app-badge>
              }
              <app-button variant="danger" [disabled]="busy()" (pressed)="remove(a.id)" data-testid="alarm-delete">{{ 'market.alarm.delete' | t }}</app-button>
            </span>
          </li>
        } @empty {
          <li class="text-[12px] text-muted">{{ 'market.alarm.none' | t }}</li>
        }
      </ul>
      <p class="mt-2 text-[11px] text-muted">{{ 'market.alarm.hint' | t: { max: data()?.maxActive ?? 0 } }}</p>
    </app-card>
  `,
})
export class PriceAlarmCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** Current prices of the Agrarbörse (fill types and sell points for the form). */
  readonly prices = input<PriceView[]>([]);

  readonly data = signal<PriceAlarmsView | null>(null);
  readonly fillType = signal('');
  readonly sellPoint = signal('');
  readonly direction = signal<'ABOVE' | 'BELOW'>('ABOVE');
  readonly threshold = signal<number | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly fillTypes = computed(() => [...new Set(this.prices().map((p) => p.fillType))].sort());
  readonly sellPoints = computed(() => {
    const seen = new Map<string, string>();
    this.prices().filter((p) => !this.fillType() || p.fillType === this.fillType()).forEach((p) => seen.set(p.sellPoint, p.sellPointName));
    return [...seen.entries()].map(([id, name]) => ({ id, name }));
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      untracked(() => this.load());
    });
    effect(() => {
      const types = this.fillTypes();
      if (!this.fillType() && types.length) untracked(() => this.fillType.set(types[0]));
    });
  }

  load(): void {
    this.api.priceAlarms().subscribe({ next: (d) => this.data.set(d), error: () => this.data.set(null) });
  }

  setThreshold(value: string): void {
    const n = Number(value);
    this.threshold.set(value === '' || !Number.isFinite(n) ? null : n);
  }

  sellPointName(id: string): string {
    return this.prices().find((p) => p.sellPoint === id)?.sellPointName ?? id;
  }

  create(): void {
    const t = this.threshold();
    if (!this.fillType() || !t) return;
    this.run(this.api.createPriceAlarm(this.fillType(), this.sellPoint() || null, t, this.direction()));
  }

  reactivate(id: number): void {
    this.run(this.api.reactivatePriceAlarm(id));
  }

  remove(id: number): void {
    this.run(this.api.deletePriceAlarm(id));
  }

  private run(call: Observable<unknown>): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
