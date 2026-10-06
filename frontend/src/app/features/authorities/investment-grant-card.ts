import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { GrantStatusView, GrantView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3.1 R31-B2: "Investitionsförderung" in the app "Ämter". Application (kind, planned sum) before a purchase;
 * after the approval the purchases in the game count, the proof is submitted by button (or automatically at the end
 * of the deadline). Paid grants show the funded machines and the binding period.
 */
@Component({
  selector: 'app-investment-grant-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Badge, Button, Card],
  template: `
    <app-card [title]="'authorities.grant.title' | t" [highlight]="highlighted()" data-testid="investment-grant">
      @if (status(); as s) {
        <p class="mb-3 text-[12px] text-muted">{{ 'authorities.grant.intro' | t: { percent: s.grantPercent, max: (s.grantMax | money), months: s.purchaseMonths, binding: s.bindingMonths } }}</p>
        @if (s.enabled) {
          <div class="mb-3 flex flex-wrap items-end gap-2" data-testid="grant-form">
            <label class="space-y-1">
              <span class="fp-label">{{ 'authorities.grant.kind' | t }}</span>
              <select class="fp-input w-40" [value]="kind()" (change)="kind.set($any($event.target).value)" data-testid="grant-kind">
                <option value="BUILDING">{{ 'BUILDING' | label: 'grantKind' }}</option>
                <option value="MACHINE">{{ 'MACHINE' | label: 'grantKind' }}</option>
              </select>
            </label>
            <label class="space-y-1">
              <span class="fp-label">{{ 'authorities.grant.plannedSum' | t: { min: (s.minSum | money) } }}</span>
              <input class="fp-input w-36 font-mono" type="number" [min]="s.minSum" step="1000" [value]="sum() ?? ''"
                (input)="setSum($any($event.target).valueAsNumber)" data-testid="grant-sum" />
            </label>
            <app-button [disabled]="busy() || !sum()" (pressed)="apply()" data-testid="grant-apply">{{ 'authorities.grant.apply' | t }}</app-button>
          </div>
          <p class="mb-3 text-[11px] text-muted">{{ 'authorities.grant.processing' | t: { days: s.processingDays } }}</p>
        }
        <ul class="space-y-2">
          @for (g of s.grants; track g.id) {
            <li class="rounded-xl border border-border p-3 text-[12px]" data-testid="grant" [attr.data-status]="g.status">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <span class="font-display font-bold text-text">{{ g.kind | label: 'grantKind' }} · {{ 'authorities.grant.planned' | t: { amount: (g.plannedSum | money) } }}</span>
                <app-badge [variant]="g.status === 'EXPIRED' ? 'negative' : g.status === 'PAID' ? 'positive' : 'warning'">{{ g.status | label: 'grantStatus' }}</app-badge>
              </div>
              @if (g.status === 'APPLIED') {
                <p class="mt-1 text-muted">{{ 'authorities.grant.approvalDue' | t: { time: (g.approvalDueGameTime | gameTime) } }}</p>
              }
              @if (g.status === 'APPROVED') {
                <p class="mt-1 text-text">{{ 'authorities.grant.recognised' | t: { amount: (g.recognisedSum | money), time: (g.purchaseDeadlineGameTime | gameTime) } }}</p>
                <div class="mt-2">
                  <app-button variant="secondary" [disabled]="busy() || !g.recognisedSum" (pressed)="proof(g)" data-testid="grant-proof">{{ 'authorities.grant.proof' | t }}</app-button>
                </div>
              }
              @if (g.status === 'PAID') {
                <p class="mt-1 text-text" data-testid="grant-paid">{{ 'authorities.grant.paid' | t: { amount: (g.grantAmount | money), recognised: (g.recognisedSum | money) } }}</p>
                @if (g.kind === 'MACHINE' && g.bindingEndsGameTime) {
                  <p class="mt-1 text-muted">{{ 'authorities.grant.binding' | t: { time: (g.bindingEndsGameTime | gameTime), count: g.objects.length } }}</p>
                }
                @if (g.repaidAmount > 0) {
                  <p class="mt-1 text-danger" data-testid="grant-repaid">{{ 'authorities.grant.repaid' | t: { amount: (g.repaidAmount | money) } }}</p>
                }
              }
            </li>
          } @empty {
            <li class="text-sm text-muted">{{ 'authorities.grant.none' | t }}</li>
          }
        </ul>
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
      @if (info(); as i) { <p class="mt-2 text-[12px] text-accent" data-testid="grant-info">{{ i }}</p> }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="grant-error">{{ e }}</p> }
    </app-card>
  `,
})
export class InvestmentGrantCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);

  /** `?grant=` of a mail link. */
  readonly highlight = input<number | null>(null);

  readonly status = signal<GrantStatusView | null>(null);
  readonly kind = signal('MACHINE');
  /** Planned sum (form field; numbers only via inputs). */
  readonly sum = signal<number | null>(null);
  readonly busy = signal(false);
  readonly info = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly highlighted = computed(() => {
    const id = this.highlight();
    return id !== null && (this.status()?.grants.some((g) => g.id === id) ?? false);
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.investmentGrants().subscribe({
      next: (s) => this.status.set(s),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  setSum(value: number): void {
    this.sum.set(Number.isFinite(value) && value > 0 ? Math.round(value) : null);
  }

  apply(): void {
    const sum = this.sum();
    if (!sum) return;
    this.run(this.api.applyGrant(this.kind(), sum), 'authorities.grant.applied');
  }

  proof(g: GrantView): void {
    this.run(this.api.grantProof(g.id), 'authorities.grant.proofSent');
  }

  private run(o: ReturnType<ApiService['grantProof']>, okKey: string): void {
    this.busy.set(true);
    this.info.set(null);
    this.error.set(null);
    o.subscribe({
      next: () => {
        this.busy.set(false);
        this.info.set(this.i18n.t(okKey));
        this.load();
        this.store.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
