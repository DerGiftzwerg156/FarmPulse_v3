import { Component, inject, OnInit, signal } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CartPosition, CartView, TypeOption } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { DealerConfigurator } from './dealer-configurator';
import { deliveryText, typeTitle } from './dealer-format';

/**
 * Roadmap vehicle dealer RFH-K3 (owner decisions 2026-10-15): the cart, stored per savegame, at most 10 positions -
 * name, deviating options, list price and delivery time per position; "Ändern" opens the configurator, "Entfernen".
 * Asking for the offer comes with the next area (B).
 */
@Component({
  selector: 'app-dealer-cart',
  imports: [TranslatePipe, MoneyPipe, Button, Card, DealerConfigurator],
  template: `
    @if (editing(); as pos) {
      <app-dealer-configurator [xmlFilename]="pos.xmlFilename" [initialChoice]="pos.choice" [positionId]="pos.id"
        (saved)="onSaved($event)" (cancelled)="editing.set(null)" />
    } @else {
      <app-card data-testid="cart">
        @if (cart(); as c) {
          <ul class="space-y-2">
            @for (p of c.positions; track p.id) {
              <li class="flex flex-wrap items-start justify-between gap-3 rounded-xl border border-border bg-bg px-3 py-2 text-[13px]"
                data-testid="cart-position">
                <div class="min-w-0">
                  <div class="text-text">{{ p.name }}</div>
                  @if (p.quote; as q) {
                    @if (q.deviations.length > 0) {
                      <div class="text-[12px] text-muted" data-testid="deviations">{{ deviations(q.deviations) }}</div>
                    }
                    <div class="text-[12px] text-muted" data-testid="position-delivery">{{ 'dealer.config.delivery' | t }}: {{ delivery(p) }}</div>
                  }
                </div>
                <div class="flex items-center gap-2">
                  <span class="font-mono text-text" data-testid="position-price">{{ p.quote ? (p.quote.listPrice | money) : '–' }}</span>
                  <app-button variant="secondary" [disabled]="!p.quote" (pressed)="editing.set(p)" data-testid="position-change">
                    {{ 'dealer.cart.change' | t }}</app-button>
                  <app-button variant="danger" [disabled]="busy()" (pressed)="remove(p)" data-testid="position-remove">
                    {{ 'dealer.cart.remove' | t }}</app-button>
                </div>
              </li>
            } @empty {
              <li class="text-[13px] text-muted" data-testid="cart-empty">{{ 'dealer.cart.empty' | t }}</li>
            }
          </ul>
          <div class="mt-3 flex flex-wrap items-center justify-between gap-2 text-[13px]">
            <span><span class="fp-label">{{ 'dealer.cart.sum' | t }}</span>
              <span class="ml-2 font-mono text-text" data-testid="cart-sum">{{ c.listPriceSum | money }}</span></span>
            <span class="fp-label" data-testid="cart-count">{{ 'dealer.cart.count' | t: { count: c.positions.length, max: c.maxPositions } }}</span>
          </div>
          @if (c.positions.length >= c.maxPositions) {
            <p class="mt-2 text-[12px] text-warn" data-testid="cart-full">{{ 'dealer.cart.full' | t: { max: c.maxPositions } }}</p>
          }
          <p class="mt-2 text-[12px] text-muted">{{ 'dealer.cart.offerLater' | t }}</p>
        }
        @if (error(); as e) {
          <p class="mt-2 text-[12px] text-danger">{{ e }}</p>
        }
      </app-card>
    }
  `,
})
export class DealerCart implements OnInit {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly cart = signal<CartView | null>(null);
  readonly editing = signal<CartPosition | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.api.dealerCart().subscribe({ next: (c) => this.cart.set(c), error: () => this.cart.set(null) });
  }

  deviations(list: TypeOption[]): string {
    return list.map((d) => `${typeTitle(this.i18n, d.type, d.title)}: ${d.option}`).join(' · ');
  }

  delivery(p: CartPosition): string {
    return p.quote ? deliveryText(this.i18n, p.quote.delivery) : '';
  }

  remove(p: CartPosition): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.dealerCartRemove(p.id).subscribe({
      next: (c) => {
        this.busy.set(false);
        this.cart.set(c);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  onSaved(c: CartView): void {
    this.editing.set(null);
    this.cart.set(c);
  }
}
