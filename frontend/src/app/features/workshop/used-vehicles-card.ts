import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Observable } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { NegotiationView, OwnVehicleView, VehicleDealView, VehiclesView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3 R3-V2 / R3-V3: used machines in the workshop app - offers of the workshop and the neighbours with the
 * negotiation form, the delivery, own machines with "Zum Verkauf anbieten", the neighbours' offers and the history.
 * Amounts only from the form fields.
 */
@Component({
  selector: 'app-used-vehicles-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Badge, Button, Card],
  template: `
    <app-card [title]="'workshop.usedVehicles' | t" data-testid="used-vehicles">
      <p class="mb-3 text-[12px] text-muted">{{ 'workshop.usedIntro' | t: { rounds: data()?.maxRounds ?? 3 } }}</p>
      @if (error()) {
        <p class="mb-2 text-[12px] text-danger" data-testid="vehicle-error">{{ error() }}</p>
      }

      <h3 class="mb-2 fp-label">{{ 'workshop.offers' | t }}</h3>
      <ul class="space-y-2">
        @for (d of buying(); track d.id) {
          @let n = d.negotiations[0];
          <li class="rounded-md border border-warn/50 bg-bg p-3 text-[12px]" data-testid="vehicle-offer" [class.ring-1]="highlighted(d)">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="font-display text-[13px] font-bold text-text">{{ d.vehicleName }}</span>
              <span class="fp-label">{{ d.character?.name }} · {{ ('workshop.seller.' + d.sellerKind) | t }}</span>
            </div>
            <div class="mt-1 text-muted">{{ 'workshop.usedValues' | t: { years: years(d), hours: d.operatingHours, damage: percent(d.damage) } }}</div>
            @if (d.status === 'OPEN' && n) {
              <div class="mt-1 text-text" data-testid="vehicle-price">{{ 'workshop.price' | t: { price: (n.lastCounterOffer ?? d.basePrice) | money } }}
                · {{ 'tasks.round' | t: { used: n.roundsUsed, max: n.maxRounds } }}
                @if (n.closesAtGameTime !== null) { · {{ 'contracts.validUntil' | t: { time: (n.closesAtGameTime | gameTime) } }} }</div>
              <form class="mt-2 flex flex-wrap items-end gap-2" (submit)="$event.preventDefault(); offer(n)">
                <label class="flex flex-col gap-1 text-muted">{{ 'workshop.yourOffer' | t }}
                  <input class="fp-input w-32 font-mono" type="number" min="1" step="1" [value]="amount(n.id) ?? ''"
                    (input)="setAmount(n.id, $any($event.target).value)" data-testid="vehicle-amount" />
                </label>
                <app-button type="submit" [disabled]="busy() || !amount(n.id)" data-testid="vehicle-offer-submit">{{ 'workshop.makeOffer' | t }}</app-button>
                <app-button variant="secondary" [disabled]="busy()" (pressed)="withdraw(n)" data-testid="vehicle-offer-decline">{{ 'contracts.decline' | t }}</app-button>
              </form>
            } @else if (d.status === 'AGREED') {
              <div class="mt-1" data-testid="vehicle-delivery">
                <app-badge variant="positive">{{ 'workshop.delivering' | t: { price: (d.finalPrice | money) } }}</app-badge>
                @if (d.nextAttemptGameTime !== null) {
                  <p class="mt-1 text-warn">{{ 'workshop.noSpace' | t: { attempt: d.attempts, max: data()?.spawnMaxAttempts ?? 5, time: (d.nextAttemptGameTime | gameTime) } }}</p>
                }
              </div>
            }
          </li>
        } @empty {
          <li class="text-[12px] text-muted">{{ 'workshop.noOffers' | t }}</li>
        }
      </ul>

      <h3 class="mb-2 mt-4 fp-label">{{ 'workshop.ownVehicles' | t }}</h3>
      <ul class="space-y-1.5">
        @for (v of data()?.vehicles ?? []; track v.uniqueId) {
          <li class="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-bg px-2.5 py-1.5 text-[12px]" data-testid="own-vehicle">
            <span class="text-text">{{ v.name ?? v.uniqueId }} · {{ 'workshop.value' | t: { value: (v.value | money) } }}
              @if (v.condition !== null) { · {{ 'workshop.condition' | t: { condition: v.condition } }} }</span>
            @if (v.saleDealId !== null) {
              <app-badge>{{ 'workshop.onSale' | t }}</app-badge>
            } @else {
              <form class="flex items-end gap-2" (submit)="$event.preventDefault(); sell(v)">
                <input class="fp-input w-32 font-mono" type="number" min="1" step="1" [value]="asking(v) ?? ''"
                  (input)="setAsking(v.uniqueId, $any($event.target).value)" data-testid="vehicle-asking" />
                <app-button type="submit" variant="secondary" [disabled]="busy() || !asking(v)" data-testid="vehicle-sell">{{ 'workshop.sell' | t }}</app-button>
              </form>
            }
          </li>
        } @empty {
          <li class="text-[12px] text-muted">{{ 'workshop.noVehicles' | t }}</li>
        }
      </ul>

      @if (selling().length) {
        <h3 class="mb-2 mt-4 fp-label">{{ 'workshop.sales' | t }}</h3>
        <ul class="space-y-2">
          @for (d of selling(); track d.id) {
            <li class="rounded-md border border-warn/50 bg-bg p-3 text-[12px]" data-testid="vehicle-sale" [class.ring-1]="highlighted(d)">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <span class="font-display text-[13px] font-bold text-text">{{ d.vehicleName }}</span>
                <span class="fp-label">{{ 'workshop.asking' | t: { price: (d.askingPrice | money) } }} · {{ 'workshop.value' | t: { value: (d.gamePrice | money) } }}</span>
              </div>
              @if (d.status === 'AGREED') {
                <app-badge variant="positive">{{ 'workshop.sold' | t: { price: (d.finalPrice | money), name: d.character?.name ?? '–' } }}</app-badge>
              }
              @for (n of openOf(d); track n.id) {
                <form class="mt-2 flex flex-wrap items-end gap-2 border-t border-border pt-2" (submit)="$event.preventDefault(); offer(n)" data-testid="vehicle-buyer">
                  <span class="min-w-40 text-text">{{ n.counterpart?.name }}: {{ 'workshop.bid' | t: { price: (n.lastCounterOffer | money) } }}
                    · {{ 'tasks.round' | t: { used: n.roundsUsed, max: n.maxRounds } }}</span>
                  <app-button variant="secondary" [disabled]="busy() || !n.lastCounterOffer" (pressed)="accept(n)" data-testid="vehicle-accept-bid">{{ 'workshop.acceptBid' | t }}</app-button>
                  <input class="fp-input w-32 font-mono" type="number" min="1" step="1" [value]="amount(n.id) ?? ''"
                    (input)="setAmount(n.id, $any($event.target).value)" data-testid="vehicle-demand" />
                  <app-button type="submit" [disabled]="busy() || !amount(n.id)" data-testid="vehicle-demand-submit">{{ 'workshop.demand' | t }}</app-button>
                </form>
              }
              @if (d.status === 'OPEN' && openOf(d).length) {
                <app-button class="mt-2 block" variant="danger" [disabled]="busy()" (pressed)="withdraw(openOf(d)[0])" data-testid="vehicle-sale-withdraw">{{ 'workshop.withdrawSale' | t }}</app-button>
              }
            </li>
          }
        </ul>
      }
      <p class="mt-2 text-[11px] text-muted">{{ 'workshop.saleHint' | t: { cap: data()?.saleCapPercent ?? 110 } }}</p>

      @if (history().length) {
        <h3 class="mb-2 mt-4 fp-label">{{ 'contracts.history' | t }}</h3>
        <ul class="space-y-1">
          @for (d of history(); track d.id) {
            <li class="flex flex-wrap items-center justify-between gap-2 border-t border-border py-1.5 text-[12px]" data-testid="vehicle-history">
              <span class="text-text">{{ d.direction | label: 'tradeDirection' }} · {{ d.vehicleName }}
                @if (d.finalPrice !== null) { · {{ d.finalPrice | money }} }
                @if (d.failureReason) { · {{ d.failureReason | label: 'vehicleFailure' }} }</span>
              <span class="fp-label">{{ d.status | label: 'vehicleDealStatus' }}@if (d.closedGameTime !== null) { · {{ d.closedGameTime | gameTime }} }</span>
            </li>
          }
        </ul>
      }
    </app-card>
  `,
})
export class UsedVehiclesCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?negotiation=` highlights a deal (links from mails and "Aufgaben"). */
  readonly highlightNegotiation = input<number | null>(null);
  readonly changed = output<void>();

  readonly data = signal<VehiclesView | null>(null);
  readonly amounts = signal<Record<number, number | null>>({});
  readonly askingPrices = signal<Record<string, number | null>>({});
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly buying = computed(() => (this.data()?.deals ?? [])
    .filter((d) => d.direction === 'BUY' && (d.status === 'OPEN' || d.status === 'AGREED')));
  readonly selling = computed(() => (this.data()?.deals ?? [])
    .filter((d) => d.direction === 'SELL' && (d.status === 'OPEN' || d.status === 'AGREED')));
  readonly history = computed(() => (this.data()?.deals ?? [])
    .filter((d) => d.status === 'DONE' || d.status === 'FAILED' || d.status === 'ENDED'));

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.vehicles().subscribe({ next: (d) => this.data.set(d), error: () => this.data.set(null) });
  }

  years(d: VehicleDealView): string {
    return d.ageMonths === null ? '–' : (Math.round((d.ageMonths / 12) * 10) / 10).toLocaleString('de-DE');
  }

  percent(v: number | null): number {
    return v === null ? 0 : Math.round(v * 100);
  }

  highlighted(d: VehicleDealView): boolean {
    const id = this.highlightNegotiation();
    return id !== null && d.negotiations.some((n) => n.id === id);
  }

  openOf(d: VehicleDealView): NegotiationView[] {
    return d.negotiations.filter((n) => n.status === 'OPEN');
  }

  amount(id: number): number | null {
    return this.amounts()[id] ?? null;
  }

  setAmount(id: number, value: string): void {
    const n = Number(value);
    this.amounts.update((a) => ({ ...a, [id]: value === '' || !Number.isFinite(n) ? null : n }));
  }

  /** Asking price of an own machine: the form value, else the game value. */
  asking(v: OwnVehicleView): number | null {
    const set = this.askingPrices()[v.uniqueId];
    return set === undefined ? v.value || null : set;
  }

  setAsking(id: string, value: string): void {
    const n = Number(value);
    this.askingPrices.update((a) => ({ ...a, [id]: value === '' || !Number.isFinite(n) ? null : n }));
  }

  offer(n: NegotiationView): void {
    const amount = this.amount(n.id);
    if (amount) this.run(this.api.offer(n.id, amount));
  }

  accept(n: NegotiationView): void {
    if (n.lastCounterOffer) this.run(this.api.offer(n.id, n.lastCounterOffer));
  }

  withdraw(n: NegotiationView): void {
    this.run(this.api.withdraw(n.id));
  }

  sell(v: OwnVehicleView): void {
    const price = this.asking(v);
    if (price) this.run(this.api.offerVehicleForSale(v.uniqueId, price));
  }

  private run(call: Observable<unknown>): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.load();
        this.changed.emit();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
