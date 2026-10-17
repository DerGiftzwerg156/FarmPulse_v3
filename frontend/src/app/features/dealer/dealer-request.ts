import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CartPosition, DealerChoice, DeliveryPlacesView, TradeInCandidateView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { Button } from '../../shared/ui/button';

/** Value of the select "Lieferort" for the shop place of the game. */
const SHOP = 'shop';

/**
 * Roadmap vehicle dealer RFH-B1 / B4 / O2 / L3 (owner decisions 2026-10-15/16): the request in the cart - trade-ins
 * ("In Zahlung geben" with the game value), "zusammen liefern" / "einzeln liefern", delivery point (the shop place of the
 * game and the valid delivery points, preset with the default) and express; "Angebot anfragen" moves the positions into
 * the request (the cart is empty afterwards).
 */
@Component({
  selector: 'app-dealer-request',
  imports: [TranslatePipe, MoneyPipe, Button],
  template: `
    <div class="mt-4 space-y-3 border-t border-border pt-3 text-[13px]" data-testid="request-form">
      <div>
        <h3 class="fp-label mb-1">{{ 'dealer.request.tradeIn' | t }}</h3>
        <ul class="space-y-1">
          @for (v of candidates(); track v.uniqueId) {
            <li data-testid="trade-in">
              <label class="flex flex-wrap items-center gap-2">
                <input type="checkbox" [checked]="tradeIns().includes(v.uniqueId)" (change)="toggleTradeIn(v.uniqueId)"
                  data-testid="trade-in-check" />
                <span>{{ 'dealer.request.giveInTrade' | t }}</span>
                <span class="text-text">{{ v.name }}</span>
                <span class="text-muted">{{ 'dealer.request.gameValue' | t: { value: (v.value | money) } }}</span>
              </label>
            </li>
          } @empty {
            <li class="text-muted" data-testid="trade-in-empty">{{ 'dealer.request.noTradeIn' | t }}</li>
          }
        </ul>
      </div>

      <div>
        <h3 class="fp-label mb-1">{{ 'dealer.request.delivery' | t }}</h3>
        <div class="flex flex-wrap gap-4">
          <label class="flex items-center gap-1"><input type="radio" name="together" [checked]="together()"
            (change)="together.set(true)" data-testid="together" /> {{ 'dealer.request.together' | t }}</label>
          <label class="flex items-center gap-1"><input type="radio" name="together" [checked]="!together()"
            (change)="together.set(false)" data-testid="separately" /> {{ 'dealer.request.separately' | t }}</label>
        </div>
      </div>

      @if (together()) {
        <div class="flex flex-wrap items-end gap-4" data-testid="choice-all">
          <label class="flex flex-col gap-1"><span class="fp-label">{{ 'dealer.request.place' | t }}</span>
            <select class="fp-input" (change)="setPlace(null, $any($event.target).value)" data-testid="place">
              @for (o of placeOptions(); track o.value) { <option [value]="o.value" [selected]="o.value === placeOf(null)">{{ o.label }}</option> }
            </select></label>
          <label class="flex items-center gap-1"><input type="checkbox" [checked]="expressOf(null)"
            (change)="setExpress(null, $any($event.target).checked)" data-testid="express" /> {{ 'dealer.request.express' | t }}</label>
        </div>
      } @else {
        <ul class="space-y-2">
          @for (p of positions(); track p.id) {
            <li class="flex flex-wrap items-end gap-4" data-testid="choice-position">
              <span class="min-w-40 text-text">{{ p.name }}</span>
              <label class="flex flex-col gap-1"><span class="fp-label">{{ 'dealer.request.place' | t }}</span>
                <select class="fp-input" (change)="setPlace(p.id, $any($event.target).value)" data-testid="place">
                  @for (o of placeOptions(); track o.value) { <option [value]="o.value" [selected]="o.value === placeOf(p.id)">{{ o.label }}</option> }
                </select></label>
              <label class="flex items-center gap-1"><input type="checkbox" [checked]="expressOf(p.id)"
                (change)="setExpress(p.id, $any($event.target).checked)" data-testid="express" /> {{ 'dealer.request.express' | t }}</label>
            </li>
          }
        </ul>
      }

      <app-button [disabled]="busy() || !ready()" (pressed)="request()" data-testid="request-offer">{{ 'dealer.request.ask' | t }}</app-button>
      @if (error(); as e) {
        <p class="text-[12px] text-danger" data-testid="request-error">{{ e }}</p>
      }
    </div>
  `,
})
export class DealerRequest implements OnInit {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly positions = input.required<CartPosition[]>();
  /** The request was sent (the cart is empty now). */
  readonly requested = output<void>();

  readonly candidates = signal<TradeInCandidateView[]>([]);
  readonly places = signal<DeliveryPlacesView | null>(null);
  readonly tradeIns = signal<string[]>([]);
  readonly together = signal(true);
  /** Choices by cart position id; key 0 = "zusammen". */
  readonly choices = signal<Record<number, DealerChoice>>({});
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly ready = computed(() => this.positions().length > 0 && this.positions().every((p) => p.quote !== null));
  readonly placeOptions = computed(() => {
    const v = this.places();
    const out = [{ value: SHOP, label: this.i18n.t('dealer.places.shopPlace') }];
    for (const p of v?.points ?? []) {
      if (p.valid) out.push({ value: String(p.id), label: p.name ?? this.i18n.t('dealer.places.pointName', { seq: p.seq }) });
    }
    return out;
  });

  ngOnInit(): void {
    this.api.dealerTradeInCandidates().subscribe({ next: (c) => this.candidates.set(c), error: () => this.candidates.set([]) });
    this.api.dealerDeliveryPoints().subscribe({ next: (v) => this.places.set(v), error: () => this.places.set(null) });
  }

  /** The default delivery point (preset), null = the shop place. */
  private defaultPoint(): number | null {
    const v = this.places();
    const d = v?.shopIsDefault ? undefined : v?.points.find((p) => p.isDefault && p.valid);
    return d ? d.id : null;
  }

  private choice(id: number | null): DealerChoice {
    return this.choices()[id ?? 0] ?? { deliveryPointId: this.defaultPoint(), express: false };
  }

  placeOf(id: number | null): string {
    const p = this.choice(id).deliveryPointId;
    return p === null ? SHOP : String(p);
  }

  expressOf(id: number | null): boolean {
    return this.choice(id).express;
  }

  setPlace(id: number | null, value: string): void {
    const c = this.choice(id);
    this.choices.update((all) => ({ ...all, [id ?? 0]: { ...c, deliveryPointId: value === SHOP ? null : Number(value) } }));
  }

  setExpress(id: number | null, express: boolean): void {
    const c = this.choice(id);
    this.choices.update((all) => ({ ...all, [id ?? 0]: { ...c, express } }));
  }

  toggleTradeIn(id: string): void {
    this.tradeIns.update((list) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]));
  }

  request(): void {
    const per: Record<number, DealerChoice> = {};
    if (!this.together()) {
      for (const p of this.positions()) per[p.id] = this.choice(p.id);
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.dealerRequest({ together: this.together(), tradeIns: this.tradeIns(),
      all: this.together() ? this.choice(null) : null, perPosition: this.together() ? null : per }).subscribe({
      next: () => {
        this.busy.set(false);
        this.tradeIns.set([]);
        this.requested.emit();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
