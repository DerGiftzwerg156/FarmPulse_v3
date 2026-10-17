import { Component, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { DealerDeliveryView, DealerDemoState, DealerOrdersView, DealerOrderView, DeliveryPlacesView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/** Value of the select "Lieferort" for the shop place of the game. */
const SHOP = 'shop';

/** Days with at most one decimal ("2,5"). */
function days(v: number): string {
  return (Math.round(v * 10) / 10).toLocaleString('de-DE');
}

/**
 * Roadmap vehicle dealer RFH-B / L (owner decisions 2026-10-15/16): the tab "Bestellungen" - requests, the dealer's
 * offer with the negotiation ("Dein Gebot" / "Bieten", "Zum Angebotspreis annehmen", "Ablehnen", "Zurück in den
 * Warenkorb"), the order with its deliveries (date, delivery point changeable until it is sent, "Finanzieren",
 * "Stornieren" with the refund, "Lieferung erneut versuchen" after a failure) and the history. Amounts only from the
 * form fields; the dealer's limits are never shown. RFH-V1: on top the dealer's demo machine ("Vorführung").
 */
@Component({
  selector: 'app-dealer-orders',
  imports: [RouterLink, TranslatePipe, MoneyPipe, GameTimePipe, Badge, Button, Card],
  template: `
    @if (demoShown(); as d) {
      <!-- RFH-V1: the dealer's demo machine (running or the last one), "In den Warenkorb" with the standard configuration -->
      <app-card [title]="'dealer.demo.title' | t" class="mb-4 block" data-testid="demo">
        <div class="flex flex-wrap items-center justify-between gap-2 text-[13px]">
          <div>
            @if (d.status === 'DELIVERING') {
              <span class="text-text" data-testid="demo-text">{{ d.machine }} · {{ 'dealer.demo.delivering' | t }}</span>
            } @else if (d.status === 'ACTIVE' || d.status === 'RETURNING') {
              <span class="text-text" data-testid="demo-text">{{ 'dealer.demo.standsUntil' | t: { machine: d.machine, time: (d.untilGameTime | gameTime) } }}</span>
            } @else {
              <span class="text-text" data-testid="demo-text">{{ d.machine }}</span>
            }
            @if (d.lateReason) {
              <div class="text-[12px] text-warn" data-testid="demo-late">{{ 'dealer.demo.late' | t: { reason: d.lateReason, fee: (demo()!.lateFee | money) } }}</div>
            }
          </div>
          <app-button variant="secondary" [disabled]="busy() || demoAdded() === d.id" (pressed)="demoToCart(d.id, d.xmlFilename)"
            data-testid="demo-to-cart">{{ 'dealer.demo.toCart' | t }}</app-button>
        </div>
      </app-card>
    }
    <app-card data-testid="orders">
      @if (error(); as e) {
        <p class="mb-2 text-[12px] text-danger" data-testid="orders-error">{{ e }}</p>
      }
      <ul class="space-y-3">
        @for (o of view()?.orders ?? []; track o.id) {
          <li class="rounded-xl border border-border bg-bg p-3 text-[13px]" data-testid="order">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="font-display text-[13px] font-bold text-text" data-testid="order-machines">{{ o.machines }}</span>
              <span class="flex flex-wrap gap-1">
                <app-badge [variant]="statusVariant(o)" data-testid="order-status">{{ statusText(o) }}</app-badge>
                @if (waiting(o)) { <app-badge variant="warning" data-testid="waiting-badge">{{ 'dealer.orders.status.WAITING' | t }}</app-badge> }
              </span>
            </div>

            @if (o.status === 'OFFERED' || o.status === 'REQUESTED' || o.status === 'REJECTED' || o.status === 'EXPIRED') {
              <!-- the offer: positions, trade-ins, offer price, deposit, validity -->
              @for (d of o.deliveries; track d.id) {
                @for (i of d.items; track i.id) {
                  <div class="mt-2 flex flex-wrap justify-between gap-2" data-testid="offer-item">
                    <span class="text-text">{{ i.name }}</span>
                    <span class="text-muted">{{ 'dealer.orders.listPrice' | t }} <span class="font-mono text-text">{{ i.listPrice | money }}</span>
                      · {{ 'dealer.orders.deliveryTime' | t: { min: days(i.daysMin), max: days(i.daysMax) } }}
                      @if (i.expressFee > 0) { · {{ 'dealer.orders.expressFee' | t: { amount: (i.expressFee | money) } }} }</span>
                  </div>
                }
                <div class="text-[12px] text-muted" data-testid="offer-fee">{{ 'dealer.orders.deliveryFee' | t: { amount: (d.deliveryFee | money) } }}
                  · {{ d.placeName }}</div>
              }
              @if (o.tradeInOffer > 0) {
                <div class="mt-1 text-muted" data-testid="offer-trade-in">{{ 'dealer.orders.tradeIn' | t: { amount: (o.tradeInOffer | money) } }}</div>
              }
              @if (o.offerPrice !== null) {
                <div class="mt-1 flex flex-wrap justify-between gap-2">
                  <span class="fp-label">{{ 'dealer.orders.offerPrice' | t }}</span>
                  <span class="font-mono text-text" data-testid="offer-price">{{ o.currentPrice | money }}</span>
                </div>
                <div class="flex flex-wrap justify-between gap-2 text-muted">
                  <span>{{ 'dealer.orders.depositAtAgreement' | t: { percent: view()!.depositPercent } }}</span>
                  <span class="font-mono" data-testid="offer-deposit">{{ o.depositAtAgreement | money }}</span>
                </div>
                @if (o.validUntilGameTime !== null && o.status === 'OFFERED') {
                  <div class="text-muted" data-testid="offer-valid">{{ 'dealer.orders.validUntil' | t: { time: (o.validUntilGameTime | gameTime) } }}</div>
                }
              }
              @if (o.status === 'OFFERED' && o.negotiation; as n) {
                <form class="mt-2 flex flex-wrap items-end gap-2" (submit)="$event.preventDefault(); bid(o)">
                  <label class="flex flex-col gap-1 text-muted">{{ 'dealer.orders.yourBid' | t }}
                    <input class="fp-input w-32 font-mono" type="number" min="1" step="1" [value]="amount(o.id) ?? ''"
                      (input)="setAmount(o.id, $any($event.target).value)" data-testid="bid-amount" /></label>
                  <app-button type="submit" [disabled]="busy() || !amount(o.id)" data-testid="bid">{{ 'dealer.orders.bid' | t }}</app-button>
                  <app-button variant="secondary" [disabled]="busy()" (pressed)="accept(o)" data-testid="accept">{{ 'dealer.orders.accept' | t }}</app-button>
                  <app-button variant="danger" [disabled]="busy()" (pressed)="decline(o)" data-testid="decline">{{ 'dealer.orders.decline' | t }}</app-button>
                  <span class="fp-label" data-testid="round">{{ 'dealer.orders.round' | t: { round: round(o), max: n.maxRounds } }}</span>
                </form>
                <a routerLink="/bank/antrag" [queryParams]="finance(o)" class="mt-2 inline-block text-[12px] text-accent" data-testid="finance">
                  {{ 'dealer.orders.finance' | t }}</a>
              }
              @if ((o.status === 'REJECTED' || o.status === 'EXPIRED') && !o.returnedToCart) {
                <app-button class="mt-2 block" variant="secondary" [disabled]="busy()" (pressed)="backToCart(o)" data-testid="back-to-cart">
                  {{ 'dealer.orders.backToCart' | t }}</app-button>
              }
            } @else {
              <!-- the order -->
              @if (o.agreedPrice !== null) {
                <div class="mt-2 flex flex-wrap justify-between gap-2">
                  <span class="fp-label">{{ 'dealer.orders.agreed' | t }}</span>
                  <span class="font-mono text-text" data-testid="agreed">{{ o.agreedPrice | money }}</span>
                </div>
                @if (o.depositPaid) {
                  <div class="flex flex-wrap justify-between gap-2 text-muted">
                    <span>{{ 'dealer.orders.depositPaid' | t }}</span>
                    <span class="font-mono" data-testid="deposit-paid">{{ o.deposit | money }}</span>
                  </div>
                }
              }
              @for (d of o.deliveries; track d.id) {
                <div class="mt-2 rounded-lg border border-border p-2" data-testid="delivery">
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <span class="text-text">{{ itemNames(d) }}</span>
                    <span class="flex flex-wrap gap-1">
                      @if (d.express) { <app-badge data-testid="express-badge">{{ 'dealer.orders.express' | t }}</app-badge> }
                      @if (d.delayed) { <app-badge variant="warning" data-testid="delayed-badge">{{ 'dealer.orders.delayed' | t }}</app-badge> }
                      @if (d.status === 'DELIVERED') { <app-badge variant="positive">{{ 'dealer.orders.status.COMPLETED' | t }}</app-badge> }
                      @if (d.status === 'CANCELLED') { <app-badge variant="negative">{{ 'dealer.orders.status.CANCELLED' | t }}</app-badge> }
                    </span>
                  </div>
                  @if (d.status === 'DELIVERED') {
                    <div class="text-muted" data-testid="delivered">{{ 'dealer.orders.delivered' | t: { time: (d.deliveredGameTime | gameTime), place: d.deliveredPlace ?? '' } }}</div>
                  } @else if (d.status !== 'CANCELLED') {
                    @if (d.rest > 0) {
                      <div class="flex flex-wrap justify-between gap-2 text-muted">
                        <span>{{ 'dealer.orders.rest' | t }}</span><span class="font-mono" data-testid="rest">{{ d.rest | money }}</span>
                      </div>
                    }
                    <div class="text-muted" data-testid="delivery-fee">{{ 'dealer.orders.deliveryFee' | t: { amount: (d.deliveryFee | money) } }}</div>
                    @if (d.dueGameTime !== null) {
                      <div class="text-text" data-testid="due">{{ 'dealer.orders.dueAt' | t: { time: (d.dueGameTime | gameTime) } }}</div>
                    }
                    @if (d.placeChangeable) {
                      <label class="mt-1 flex flex-wrap items-center gap-2"><span class="fp-label">{{ 'dealer.request.place' | t }}</span>
                        <select class="fp-input" [disabled]="busy()" (change)="changePlace(d, $any($event.target).value)"
                          data-testid="delivery-place">
                          @for (p of placeOptions(d); track p.value) {
                            <option [value]="p.value" [selected]="p.value === (d.deliveryPointId === null ? shop : '' + d.deliveryPointId)">{{ p.label }}</option>
                          }
                        </select></label>
                    } @else {
                      <div class="text-muted">{{ 'dealer.request.place' | t }}: {{ d.placeName }}</div>
                    }
                    @if (d.status === 'WAITING' && d.lastFailure) {
                      <p class="mt-1 text-warn" data-testid="last-failure">{{ 'dealer.orders.lastAttempt' | t: { reason: d.lastFailure } }}</p>
                      @if (d.retryable) {
                        <app-button class="mt-1 block" [disabled]="busy()" (pressed)="retry(d)" data-testid="retry">
                          {{ 'dealer.orders.retry' | t: { fee: (view()!.retryFee | money) } }}</app-button>
                      }
                      <p class="text-[12px] text-muted">{{ 'dealer.orders.nextDay' | t }}</p>
                    }
                    @if (d.cancellable) {
                      @if (confirming() === d.id) {
                        <div class="mt-2 flex flex-wrap items-center gap-2" data-testid="cancel-confirm">
                          <span class="text-text">{{ 'dealer.orders.cancelQuestion' | t: { amount: (d.refund | money) } }}</span>
                          <app-button variant="danger" [disabled]="busy()" (pressed)="cancel(d)" data-testid="cancel-yes">{{ 'dealer.orders.cancel' | t }}</app-button>
                          <app-button variant="secondary" (pressed)="confirming.set(null)" data-testid="cancel-no">{{ 'dealer.places.cancel' | t }}</app-button>
                        </div>
                      } @else {
                        <app-button class="mt-2 block" variant="danger" [disabled]="busy()" (pressed)="confirming.set(d.id)" data-testid="cancel">
                          {{ 'dealer.orders.cancel' | t }}</app-button>
                      }
                    }
                  }
                </div>
              }
              @if (o.status === 'ORDERED' && o.openAmount > 0) {
                <a routerLink="/bank/antrag" [queryParams]="finance(o)" class="mt-2 inline-block text-[12px] text-accent" data-testid="finance">
                  {{ 'dealer.orders.finance' | t }}</a>
              }
            }
          </li>
        } @empty {
          <li class="text-[13px] text-muted" data-testid="orders-empty">{{ 'dealer.orders.empty' | t }}</li>
        }
      </ul>
    </app-card>
  `,
})
export class DealerOrders {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly shop = SHOP;
  readonly days = days;
  readonly view = signal<DealerOrdersView | null>(null);
  readonly places = signal<DeliveryPlacesView | null>(null);
  readonly amounts = signal<Record<number, number | null>>({});
  readonly confirming = signal<number | null>(null);
  /** RFH-V1: the dealer's demo; the id of the demo whose machine was put into the cart. */
  readonly demo = signal<DealerDemoState | null>(null);
  readonly demoAdded = signal<number | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.dealerOrders().subscribe({ next: (v) => this.view.set(v), error: () => this.view.set(null) });
    this.api.dealerDeliveryPoints().subscribe({ next: (v) => this.places.set(v), error: () => this.places.set(null) });
    this.api.dealerDemo().subscribe({ next: (d) => this.demo.set(d), error: () => this.demo.set(null) });
  }

  /** The running demo, else the last finished one. */
  demoShown() {
    const d = this.demo();
    return d?.running ?? d?.last ?? null;
  }

  /** "In den Warenkorb": the demonstrated machine with its standard configuration. */
  demoToCart(id: number, xmlFilename: string): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.dealerCartAdd(xmlFilename, {}).subscribe({
      next: () => {
        this.busy.set(false);
        this.demoAdded.set(id);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  /** Status of the order; an order with a delivery waiting at the dealer additionally shows "Wartet beim Händler". */
  statusText(o: DealerOrderView): string {
    const key = o.status === 'DEPOSIT_PENDING' ? 'ORDERED' : o.status;
    return this.i18n.t('dealer.orders.status.' + key);
  }

  statusVariant(o: DealerOrderView): 'positive' | 'warning' | 'negative' | 'neutral' {
    if (o.status === 'COMPLETED' || o.status === 'ORDERED' || o.status === 'DEPOSIT_PENDING') return 'positive';
    if (o.status === 'OFFERED' || o.status === 'REQUESTED') return 'warning';
    if (o.status === 'CANCELLED' || o.status === 'REJECTED' || o.status === 'EXPIRED' || o.status === 'FAILED') return 'negative';
    return 'neutral';
  }

  waiting(o: DealerOrderView): boolean {
    return o.deliveries.some((d) => d.status === 'WAITING');
  }

  itemNames(d: DealerDeliveryView): string {
    return d.items.map((i) => i.name).join(', ');
  }

  /** "Runde 1 von 3": the round of the next bid. */
  round(o: DealerOrderView): number {
    const n = o.negotiation;
    return n ? Math.min(n.roundsUsed + 1, n.maxRounds) : 1;
  }

  /** "Finanzieren": the credit application with the amount (offer: total, order: rest) and "Neumaschine: <Namen>". */
  finance(o: DealerOrderView): Record<string, string> {
    return { amount: String(o.openAmount), purpose: this.i18n.t('dealer.orders.financePurpose', { machines: o.machines }) };
  }

  placeOptions(d: DealerDeliveryView): { value: string; label: string }[] {
    const out = [{ value: SHOP, label: this.i18n.t('dealer.places.shopPlace') }];
    for (const p of this.places()?.points ?? []) {
      if (p.valid || p.id === d.deliveryPointId) {
        out.push({ value: String(p.id), label: p.name ?? this.i18n.t('dealer.places.pointName', { seq: p.seq }) });
      }
    }
    return out;
  }

  amount(id: number): number | null {
    return this.amounts()[id] ?? null;
  }

  setAmount(id: number, value: string): void {
    const n = Number(value);
    this.amounts.update((a) => ({ ...a, [id]: value === '' || !Number.isFinite(n) ? null : n }));
  }

  bid(o: DealerOrderView): void {
    const amount = this.amount(o.id);
    if (amount) this.run(this.api.dealerBid(o.id, amount), () => this.setAmount(o.id, ''));
  }

  accept(o: DealerOrderView): void {
    this.run(this.api.dealerAccept(o.id));
  }

  decline(o: DealerOrderView): void {
    this.run(this.api.dealerDecline(o.id));
  }

  backToCart(o: DealerOrderView): void {
    this.run(this.api.dealerBackToCart(o.id));
  }

  changePlace(d: DealerDeliveryView, value: string): void {
    this.run(this.api.dealerChangePlace(d.id, value === SHOP ? null : Number(value)));
  }

  retry(d: DealerDeliveryView): void {
    this.run(this.api.dealerRetry(d.id));
  }

  cancel(d: DealerDeliveryView): void {
    this.run(this.api.dealerCancel(d.id), () => this.confirming.set(null));
  }

  private run(call: Observable<DealerOrdersView>, after?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
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
