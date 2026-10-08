import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import {
  InvestorContractView, InvestorObligationView, InvestorOfferView, InvestorPaymentView, InvestorStallView, InvestorsView,
} from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { ServiceCases } from '../contracts/service-cases';

const DELIVERED = ['W1', 'W2', 'W3', 'A1'];
const STATUS_BADGE: Record<string, BadgeVariant> = {
  ACTIVE: 'positive', ENDED: 'neutral', TERMINATED: 'negative', OFFERED: 'neutral',
};

/**
 * Roadmap V3.2 R32-I: app "Bank", area "Investoren" - the open offer with its 2-3 packages side by side ("Annehmen"
 * per package, "Ablehnen"), the contracts with their considerations, the current period, "Liefern" for goods, milk
 * and animals (amount and stable only via form fields), the investor's consent to a field sale (P1), open payments
 * and the cases (reminder, claim, first refusal, visit).
 */
@Component({
  selector: 'app-investors-panel',
  imports: [TranslatePipe, MoneyPipe, NumberPipe, GameTimePipe, Badge, Button, Card, ServiceCases],
  template: `
    <div class="space-y-4">
      <app-card [title]="'investors.title' | t" data-testid="investors">
        @if (view(); as v) {
          <p class="mb-2 text-[12px] text-muted">{{ 'investors.intro' | t: { max: v.maxActive, grace: v.graceDays, markup: percent(v.compensationMarkup - 1), breaches: v.breachesToTerminate } }}</p>
          @if (!v.enabled || !v.savegameEnabled) {
            <p class="mb-2 text-[12px] text-warn" data-testid="investors-off">{{ 'investors.off' | t }}</p>
          }
          @for (o of v.offers; track o.offer.id) {
            <div class="mb-3 rounded-md border border-accent/40 p-2" [class.ring-1]="highlightCase() === o.offer.id" data-testid="investor-offer">
              <div class="flex flex-wrap items-center justify-between gap-2 text-[13px]">
                <span class="font-semibold text-text">{{ (o.extension ? 'investors.offerExtension' : 'investors.offer') | t: { name: o.offer.character?.name ?? '–', kind: o.offer.title ?? '–', amount: (o.offer.offerAmount | money) } }}</span>
                @if (o.offer.deadlineGameTime) { <span class="text-[11px] text-muted">{{ 'investors.until' | t: { at: (o.offer.deadlineGameTime | gameTime) } }}</span> }
              </div>
              <div class="mt-2 grid grid-cols-1 gap-2 md:grid-cols-3">
                @for (p of o.packages; track p.id) {
                  <div class="flex flex-col rounded-md border border-border bg-bg p-2 text-[12px]" data-testid="investor-package">
                    <div class="font-semibold text-text">{{ 'investors.package' | t: { n: p.packageNo } }}</div>
                    <div class="text-muted">{{ ('investors.capital.' + p.capitalType) | t }} · {{ 'investors.term' | t: { years: p.years, from: p.startYear, to: p.endYear } }}</div>
                    <ul class="mt-1 list-disc pl-4 text-text">
                      @for (c of p.considerations; track c.id) {
                        <li data-testid="package-consideration">{{ describe(c) }}@if (!c.main) { <span class="text-muted"> ({{ 'investors.side' | t }})</span> }</li>
                      }
                    </ul>
                    <div class="mt-1 text-[11px] text-muted">{{ 'investors.value' | t: { value: (p.targetValue | money), percent: percent(p.targetReturn) } }}</div>
                    <div class="mt-auto pt-2"><app-button [disabled]="busy()" (pressed)="accept(o, p)" data-testid="package-accept">{{ 'contracts.accept' | t }}</app-button></div>
                  </div>
                }
              </div>
              <div class="mt-2"><app-button variant="secondary" [disabled]="busy()" (pressed)="decline(o)" data-testid="offer-decline">{{ 'contracts.decline' | t }}</app-button></div>
              <p class="mt-1 text-[11px] text-muted">{{ (o.extension ? 'investors.extensionHint' : 'investors.offerHint') | t }}</p>
            </div>
          }
          <ul class="space-y-2">
            @for (c of v.contracts; track c.id) {
              <li class="rounded-md border border-border bg-bg px-2.5 py-2 text-[12px]" data-testid="investor-contract">
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <span class="font-semibold text-text">{{ c.investor ?? '–' }} · {{ c.kindLabel }} · {{ c.amount | money }}</span>
                  <span class="flex gap-1">
                    <app-badge [variant]="badge(c.status)">{{ ('investors.status.' + c.status) | t }}</app-badge>
                    @if (c.breaches > 0) { <app-badge variant="warning" data-testid="contract-breaches">{{ 'investors.breaches' | t: { n: c.breaches, max: v.breachesToTerminate } }}</app-badge> }
                  </span>
                </div>
                <div class="text-muted">{{ ('investors.capital.' + c.capitalType) | t }} · {{ 'investors.term' | t: { years: c.years, from: c.startYear, to: c.endYear } }}@if (c.extendedBy) { · {{ 'investors.extended' | t }} }</div>
                <ul class="mt-1 space-y-1">
                  @for (o of c.considerations; track o.id) {
                    <li data-testid="contract-consideration">
                      <div class="text-text">{{ describe(o) }}</div>
                      @if (o.current; as p) {
                        @if (p.required !== null && isDelivered(o)) {
                          <div class="text-muted" data-testid="consideration-progress">{{ 'investors.progress' | t: { delivered: (progressDelivered(o) | num), required: (p.required | num), unit: unit(o) } }}</div>
                        }
                      }
                      @if (isDelivered(o) && o.outstanding > 0 && c.status !== 'TERMINATED') {
                        <div class="mt-1 flex flex-wrap items-end gap-2" data-testid="deliver-form">
                          <label class="flex flex-col gap-1 text-muted">{{ 'investors.quantity' | t: { unit: unit(o) } }}
                            <input class="fp-input w-28" type="number" min="1" [max]="o.outstanding" [value]="qty()[o.id] ?? ''"
                              (input)="setQty(o.id, +$any($event.target).value)" data-testid="deliver-quantity" />
                          </label>
                          @if (o.type === 'W3' || o.type === 'A1') {
                            <label class="flex flex-col gap-1 text-muted">{{ 'investors.stall' | t }}
                              <select class="fp-input" [value]="stall()[o.id] ?? ''" (change)="setStall(o.id, $any($event.target).value)" data-testid="deliver-stall">
                                <option value="">{{ 'investors.chooseStall' | t }}</option>
                                @for (s of stallsFor(o); track s.husbandryUniqueId) {
                                  <option [value]="s.husbandryUniqueId">{{ stallLine(o, s) }}</option>
                                }
                              </select>
                            </label>
                          }
                          <app-button [disabled]="busy() || !canDeliver(o)" (pressed)="deliver(o)" data-testid="deliver">{{ 'investors.deliver' | t }}</app-button>
                          <span class="text-[11px] text-muted">{{ 'investors.open' | t: { n: (o.outstanding | num), unit: unit(o) } }}</span>
                        </div>
                      }
                      @if (o.type === 'P1' && c.status === 'ACTIVE') {
                        <div class="mt-1 flex flex-wrap items-end gap-2" data-testid="consent-form">
                          <select class="fp-input" [value]="field()[c.id] ?? ''" (change)="setField(c.id, +$any($event.target).value)" data-testid="consent-field">
                            <option value="">{{ 'investors.chooseField' | t }}</option>
                            @for (f of v.fields; track f.farmlandId) {
                              <option [value]="f.farmlandId" [disabled]="o.consents.includes(f.farmlandId)">{{ f.name ?? ('farmland.fieldLabel' | t: { id: f.farmlandId }) }}@if (o.consents.includes(f.farmlandId)) { ✓ }</option>
                            }
                          </select>
                          <app-button variant="secondary" [disabled]="busy() || !field()[c.id]" (pressed)="consent(c)" data-testid="consent-request">{{ 'investors.consent' | t }}</app-button>
                        </div>
                      }
                      @for (b of o.breaches; track b.periodKey) {
                        <div class="text-[11px] text-warn" data-testid="consideration-breach">{{ ('investors.period.' + b.status) | t: { year: b.year, amount: (b.compensation ?? 0 | money), at: (b.graceUntil ?? 0 | gameTime) } }}</div>
                      }
                    </li>
                  }
                </ul>
                @if (c.payments.length) {
                  <ul class="mt-1 border-t border-border pt-1 text-[11px]">
                    @for (p of c.payments; track p.id) {
                      <li class="flex flex-wrap items-center gap-2" data-testid="investor-payment">
                        <span class="text-text">{{ ('investors.payment.' + p.kind) | t }}: {{ p.amount | money }}@if (p.year) { ({{ p.year }}) }</span>
                        @if (p.status === 'OPEN') {
                          <app-badge variant="negative">{{ 'investors.paymentOpen' | t }}</app-badge>
                          <app-button variant="secondary" [disabled]="busy()" (pressed)="pay(p)" data-testid="payment-pay">{{ 'investors.case.pay' | t }}</app-button>
                        } @else if (p.status === 'CLAIMED') {
                          <app-badge variant="warning">{{ 'investors.paymentClaimed' | t }}</app-badge>
                        }
                      </li>
                    }
                  </ul>
                }
              </li>
            } @empty {
              @if (!v.offers.length) { <li class="text-[12px] text-muted" data-testid="investors-none">{{ 'investors.none' | t }}</li> }
            }
          </ul>
        } @else {
          <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
        }
        @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="investors-error">{{ e }}</p> }
      </app-card>
      <app-service-cases [caseKinds]="['INVESTOR_REMINDER', 'INVESTOR_CLAIM', 'INVESTOR_PURCHASE', 'INVESTOR_VISIT']"
        openTitle="investors.cases" [highlightCase]="highlightCase()" [showEmpty]="false" testId="investor-cases" />
    </div>
  `,
})
export class InvestorsPanel {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);

  /** `?case=` of a mail link (offer, reminder, claim, request). */
  readonly highlightCase = input<number | null>(null);

  readonly view = signal<InvestorsView | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  /** Form fields per consideration / contract. */
  readonly qty = signal<Record<number, number>>({});
  readonly stall = signal<Record<number, string>>({});
  readonly field = signal<Record<number, number>>({});

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.investors().subscribe({
      next: (v) => {
        this.view.set(v);
        this.error.set(null);
      },
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  badge(status: string): BadgeVariant {
    return STATUS_BADGE[status] ?? 'neutral';
  }

  percent(rate: number | null): string {
    return (Math.round((rate ?? 0) * 1000) / 10).toLocaleString('de-DE');
  }

  isDelivered(o: InvestorObligationView): boolean {
    return DELIVERED.includes(o.type);
  }

  unit(o: InvestorObligationView): string {
    return o.type === 'A1' ? this.i18n.t('investors.animals') : 'l';
  }

  /** W1 counts the litres delivered over the term against the cumulative minimum of the year. */
  progressDelivered(o: InvestorObligationView): number {
    return o.type === 'W1' ? o.deliveredTotal : (o.current?.delivered ?? 0);
  }

  private label(group: string, value: string | null): string {
    if (!value) return '–';
    const key = `enums.${group}.${value}`;
    return this.i18n.has(key) ? this.i18n.t(key) : value;
  }

  private num(n: number | null): string {
    return (n ?? 0).toLocaleString('de-DE');
  }

  /** German text of a consideration (the same facts the backend's mails name). */
  describe(o: InvestorObligationView): string {
    const p = {
      quantity: this.num(o.quantity), min: this.num(o.minPerYear), sort: this.label('fillType', o.fillType),
      sub: o.subType ?? '–', rate: this.percent(o.rate), target: (o.target ?? 0).toLocaleString('de-DE'),
      hectares: (o.hectares ?? 0).toLocaleString('de-DE'), value: this.num(o.valuePerYear),
    };
    const key = o.type === 'A4' ? `investors.c.A4_${o.targetKind}` : `investors.c.${o.type}`;
    return this.i18n.t(key, p);
  }

  stallsFor(o: InvestorObligationView): InvestorStallView[] {
    const stalls = this.view()?.stalls ?? [];
    return o.type === 'W3' ? stalls.filter((s) => s.milk.some((m) => m.fillType === o.fillType))
      : stalls.filter((s) => s.subTypes.some((x) => x.name === o.subType && x.count > 0));
  }

  stallLine(o: InvestorObligationView, s: InvestorStallView): string {
    const type = this.label('animalType', s.animalType);
    if (o.type === 'W3') {
      const milk = s.milk.find((m) => m.fillType === o.fillType)?.amount ?? 0;
      return `${type} · ${this.num(Math.floor(milk))} l`;
    }
    const n = s.subTypes.find((x) => x.name === o.subType)?.count ?? 0;
    return `${type} · ${n} ${this.i18n.t('investors.animals')}`;
  }

  setQty(id: number, value: number): void {
    this.qty.update((m) => ({ ...m, [id]: Number.isFinite(value) ? value : 0 }));
  }

  setStall(id: number, value: string): void {
    this.stall.update((m) => ({ ...m, [id]: value }));
  }

  setField(contractId: number, value: number): void {
    this.field.update((m) => ({ ...m, [contractId]: value }));
  }

  canDeliver(o: InvestorObligationView): boolean {
    const q = this.qty()[o.id] ?? 0;
    const needsStall = o.type === 'W3' || o.type === 'A1';
    return q > 0 && q <= o.outstanding && (!needsStall || !!this.stall()[o.id]);
  }

  accept(o: InvestorOfferView, p: InvestorContractView): void {
    this.run(this.api.acceptInvestorPackage(o.offer.id, p.id));
  }

  decline(o: InvestorOfferView): void {
    this.run(this.api.caseAction(o.offer.id, 'decline'));
  }

  deliver(o: InvestorObligationView): void {
    const needsStall = o.type === 'W3' || o.type === 'A1';
    this.run(this.api.deliverToInvestor(o.id, this.qty()[o.id] ?? 0, needsStall ? this.stall()[o.id] ?? null : null));
  }

  consent(c: InvestorContractView): void {
    const id = this.field()[c.id];
    if (id) this.run(this.api.investorFieldConsent(c.id, id));
  }

  pay(p: InvestorPaymentView): void {
    this.run(this.api.payInvestorPayment(p.id));
  }

  private run(o: Observable<unknown>): void {
    this.busy.set(true);
    this.error.set(null);
    o.subscribe({
      next: () => {
        this.busy.set(false);
        this.qty.set({});
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
