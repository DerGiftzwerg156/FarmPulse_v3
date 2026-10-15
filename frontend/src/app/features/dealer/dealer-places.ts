import { Component, inject, OnInit, signal } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { DeliveryPlacesView, DeliveryPointView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { clockTime } from '../../shared/format/format';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap vehicle dealer RFH-O1 (owner decisions 2026-10-15): the delivery points set in the game by key (Alt+L) and
 * the shop place of the game (always on top, cannot be deleted). Rename (at most 40 characters), default, delete; a
 * point whose farmland no longer belongs to the farm is marked invalid.
 */
@Component({
  selector: 'app-dealer-places',
  imports: [TranslatePipe, Badge, Button, Card],
  template: `
    <app-card data-testid="places">
      @if (view(); as v) {
        <ul class="space-y-2">
          <li class="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border bg-bg px-3 py-2 text-[13px]"
            data-testid="shop-place">
            <span class="flex items-center gap-2 text-text">{{ 'dealer.places.shopPlace' | t }}
              @if (v.shopIsDefault) { <app-badge variant="positive" data-testid="default-badge">{{ 'dealer.places.default' | t }}</app-badge> }</span>
            @if (!v.shopIsDefault) {
              <app-button variant="secondary" [disabled]="busy()" (pressed)="makeDefault('shop')" data-testid="shop-default">
                {{ 'dealer.places.makeDefault' | t }}</app-button>
            }
          </li>
          @for (p of v.points; track p.id) {
            <li class="rounded-xl border border-border bg-bg px-3 py-2 text-[13px]" data-testid="delivery-point">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div class="min-w-0">
                  <div class="flex flex-wrap items-center gap-2 text-text">
                    <span data-testid="point-name">{{ name(p) }}</span>
                    @if (p.isDefault) { <app-badge variant="positive" data-testid="default-badge">{{ 'dealer.places.default' | t }}</app-badge> }
                    @if (!p.name) { <app-badge variant="warning" data-testid="unnamed-badge">{{ 'dealer.places.unnamed' | t }}</app-badge> }
                    @if (!p.valid) { <app-badge variant="negative" data-testid="invalid-badge">{{ 'dealer.places.invalid' | t }}</app-badge> }
                  </div>
                  <div class="text-[12px] text-muted">
                    @if (p.farmlandId !== null) { {{ 'dealer.places.farmland' | t: { id: p.farmlandId } }} · }
                    {{ setAt(p) }}</div>
                  @if (!p.valid) {
                    <div class="text-[12px] text-warn" data-testid="invalid-text">{{ 'dealer.places.invalidText' | t }}</div>
                  }
                </div>
                <div class="flex flex-wrap gap-2">
                  <app-button variant="secondary" [disabled]="busy()" (pressed)="startRename(p)" data-testid="point-rename">
                    {{ 'dealer.places.rename' | t }}</app-button>
                  @if (!p.isDefault && p.valid) {
                    <app-button variant="secondary" [disabled]="busy()" (pressed)="makeDefault(p.id)" data-testid="point-default">
                      {{ 'dealer.places.makeDefault' | t }}</app-button>
                  }
                  <app-button variant="danger" [disabled]="busy()" (pressed)="remove(p)" data-testid="point-delete">
                    {{ 'dealer.places.delete' | t }}</app-button>
                </div>
              </div>
              @if (renaming() === p.id) {
                <div class="mt-2 flex flex-wrap items-end gap-2">
                  <label class="flex flex-col gap-1"><span class="fp-label">{{ 'dealer.places.name' | t }}</span>
                    <input class="fp-input w-56" maxlength="40" [value]="draft()" (input)="draft.set($any($event.target).value)"
                      data-testid="rename-input" /></label>
                  <app-button [disabled]="busy() || !draft().trim()" (pressed)="saveName(p)" data-testid="rename-save">
                    {{ 'dealer.places.save' | t }}</app-button>
                  <app-button variant="secondary" (pressed)="renaming.set(null)" data-testid="rename-cancel">
                    {{ 'dealer.places.cancel' | t }}</app-button>
                </div>
              }
            </li>
          } @empty {
            <li class="text-[13px] text-muted" data-testid="places-empty">{{ 'dealer.places.empty' | t }}</li>
          }
        </ul>
      }
      @if (error(); as e) {
        <p class="mt-2 text-[12px] text-danger">{{ e }}</p>
      }
    </app-card>
  `,
})
export class DealerPlaces implements OnInit {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly view = signal<DeliveryPlacesView | null>(null);
  readonly renaming = signal<number | null>(null);
  readonly draft = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.api.dealerDeliveryPoints().subscribe({ next: (v) => this.view.set(v), error: () => this.view.set(null) });
  }

  name(p: DeliveryPointView): string {
    return p.name ?? this.i18n.t('dealer.places.pointName', { seq: p.seq });
  }

  setAt(p: DeliveryPointView): string {
    return this.i18n.t('dealer.places.setAt', { day: p.day ?? '–', time: clockTime(p.dayTimeMs) });
  }

  startRename(p: DeliveryPointView): void {
    this.draft.set(p.name ?? '');
    this.renaming.set(p.id);
  }

  saveName(p: DeliveryPointView): void {
    this.run(this.api.dealerRenameDeliveryPoint(p.id, this.draft().trim()), () => this.renaming.set(null));
  }

  makeDefault(id: number | 'shop'): void {
    this.run(this.api.dealerDefaultDeliveryPoint(id));
  }

  remove(p: DeliveryPointView): void {
    this.run(this.api.dealerDeleteDeliveryPoint(p.id));
  }

  private run(call: ReturnType<ApiService['dealerDeliveryPoints']>, after?: () => void): void {
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
