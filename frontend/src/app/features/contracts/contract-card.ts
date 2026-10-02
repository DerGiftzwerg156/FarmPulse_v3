import { Component, inject, input, output, signal } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { ContractView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';

export const CONTRACT_BADGE: Record<string, BadgeVariant> = {
  OFFERED: 'warning',
  ACTIVE: 'positive',
  DECLINED: 'neutral',
  CANCELLED: 'neutral',
  ENDED: 'neutral',
};

/**
 * One recurring contract (lease, maintenance, tax advisor ...) with its actions: accept / decline an offer, cancel,
 * renew or buy a lease. Emits `changed` after a successful action.
 */
@Component({
  selector: 'app-contract-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Badge, Button],
  template: `
    @let c = contract();
    <div class="rounded-xl border border-border bg-bg p-3" data-testid="contract" [attr.data-kind]="c.kind" [class.ring-1]="highlight()">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <span class="text-[13px] text-text">{{ c.kind | label: 'contractKind' }}@if (c.farmlandId !== null) { · {{ 'contracts.field' | t: { id: c.farmlandId } }} } ·
          {{ 'contracts.perMonth' | t: { amount: (c.monthlyAmount | money) } }}@if (c.character) { · {{ c.character.name }} }
          @if (c.status === 'ACTIVE' && c.endsAtGameTime !== null) { · {{ 'contracts.leaseEnds' | t: { time: (c.endsAtGameTime | gameTime) } }} }
          @if (c.status === 'OFFERED' && c.offerExpiresAtGameTime !== null) { · {{ 'contracts.validUntil' | t: { time: (c.offerExpiresAtGameTime | gameTime) } }} }
          @if (c.endReason) { · {{ c.endReason | label: 'endReason' }} }</span>
        <app-badge [variant]="c.paymentOverdue ? 'negative' : badge(c.status)">{{ (c.paymentOverdue ? 'contracts.overdue' : 'enums.contractStatus.' + c.status) | t }}</app-badge>
      </div>
      @if (c.status === 'OFFERED') {
        <div class="mt-2 flex gap-2">
          <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="contract-accept">{{ 'contracts.accept' | t }}</app-button>
          <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="contract-decline">{{ 'contracts.decline' | t }}</app-button>
        </div>
      }
      @if (c.kind === 'WINTER_SERVICE') {
        <!-- Roadmap V3.1 R31-A4: base fee per winter month plus a fee per snow day -->
        <p class="mt-1 text-[12px] text-muted" data-testid="winter-service">
          @if (c.status === 'ACTIVE') {
            {{ 'authorities.winter.days' | t: { month: c.snowDays ?? 0, total: c.snowDaysTotal ?? 0 } }}
          } @else if (c.status === 'ENDED') {
            {{ 'authorities.winter.total' | t: { total: c.snowDaysTotal ?? 0 } }}
          } @else {
            {{ 'authorities.winter.hint' | t }}
          }
        </p>
      }
      @if ((c.kind === 'MAINTENANCE' || c.kind === 'TAX_ADVISOR') && c.status === 'ACTIVE') {
        <div class="mt-2"><app-button variant="secondary" [disabled]="busy()" (pressed)="act('cancel')" data-testid="contract-cancel">{{ 'contracts.cancel' | t }}</app-button></div>
      }
      @if (c.kind === 'LEASE' && c.status === 'ACTIVE') {
        <div class="mt-2 flex flex-wrap gap-2">
          @if (c.renewalAmount) {
            <app-button [disabled]="busy()" (pressed)="act('renew')" data-testid="lease-renew">{{ 'contracts.renew' | t: { amount: (c.renewalAmount | money) } }}</app-button>
          }
          @if (c.purchasePrice) {
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('buy')" data-testid="lease-buy">{{ 'contracts.buy' | t: { amount: (c.purchasePrice | money) } }}</app-button>
          }
          <app-button variant="danger" [disabled]="busy()" (pressed)="act('cancel')" data-testid="lease-cancel">{{ 'contracts.endLease' | t }}</app-button>
        </div>
      }
      @if (c.kind === 'LEASE_OUT' && c.status === 'ACTIVE' && c.renewalAmount) {
        <!-- Roadmap V3 R3-L1: the tenant's renewal offer; no early termination (owner decision) -->
        <div class="mt-2"><app-button [disabled]="busy()" (pressed)="act('renew')" data-testid="lease-out-renew">{{ 'contracts.renewLeaseOut' | t: { amount: (c.renewalAmount | money) } }}</app-button></div>
      }
      @if (error(); as e) {
        <p class="mt-2 text-[12px] text-danger" data-testid="contract-error">{{ e }}</p>
      }
    </div>
  `,
})
export class ContractCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly contract = input.required<ContractView>();
  readonly highlight = input(false);
  readonly changed = output<ContractView>();
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  act(action: string): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.contractAction(this.contract().id, action).subscribe({
      next: (v) => {
        this.busy.set(false);
        this.changed.emit(v);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  badge(status: string): BadgeVariant {
    return CONTRACT_BADGE[status] ?? 'neutral';
  }
}
