import { Component, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CollateralView, CreditApplicationView, DeferralView, LoanView, SpecialRepaymentView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';
import { Stat } from '../../shared/ui/stat';
import { ServiceCases } from '../contracts/service-cases';
import { CollateralPicker } from './collateral-picker';
import { FarmReportCard } from './farm-report-card';
import { FinanceCard } from './finance-card';
import { LiquidityPlanCard } from './liquidity-plan-card';

export type ApplicationState = 'processing' | 'approved' | 'counter' | 'rejected' | 'accepted' | 'declined';

/** UI state of a credit application; the decision stays hidden while the bank is "processing" it. */
export function applicationState(a: CreditApplicationView): ApplicationState {
  if (a.status === 'PROCESSING') return 'processing';
  if (a.status === 'ACCEPTED') return a.decision === 'APPROVED' ? 'approved' : 'accepted';
  if (a.status === 'DECLINED') return 'declined';
  if (a.decision === 'COUNTER_OFFER') return 'counter';
  if (a.decision === 'REJECTED') return 'rejected';
  return 'approved';
}

export const STATE_BADGE: Record<ApplicationState, BadgeVariant> = {
  processing: 'neutral',
  approved: 'positive',
  counter: 'warning',
  rejected: 'negative',
  accepted: 'positive',
  declined: 'neutral',
};

/**
 * Bank & credit (AP-8.4): application form (numbers only via form fields), processing state until the decision
 * is visible, result with coarse reason category (never a score), counter-offer handling, running loans with
 * repayment plan/history, deferral requests and Sondertilgungen (same installment, shorter term).
 */
@Component({
  selector: 'app-bank',
  imports: [ReactiveFormsModule, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Card, Badge, Button, Stat, PageErrorView, FinanceCard,
    CollateralPicker, LiquidityPlanCard, FarmReportCard, ServiceCases],
  templateUrl: './bank.html',
})
export class Bank {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?application=` highlights an application (link from the bank's mail). */
  readonly application = input<string>();
  /** `?case=` highlights a claim, an invitation to the annual review or a rate-cut offer (R3-K). */
  readonly case = input<string>();
  readonly highlightedCase = computed(() => Number(this.case()) || null);
  private readonly picker = viewChild(CollateralPicker);
  /** Roadmap V3 R3-K1: own fields offered as collateral in the form. */
  readonly collateralIds = signal<number[]>([]);
  readonly consentBusy = signal<number | null>(null);

  readonly applications = signal<CreditApplicationView[] | null>(null);
  readonly loans = signal<LoanView[] | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly formError = signal<string | null>(null);
  readonly submitting = signal(false);
  readonly actionError = signal<Record<number, string>>({});
  readonly deferralText = signal<Record<number, string>>({});
  readonly deferralResult = signal<Record<number, DeferralView>>({});
  readonly specialAmount = signal<Record<number, number | null>>({});
  readonly specialResult = signal<Record<number, SpecialRepaymentView>>({});
  readonly specialBusy = signal<number | null>(null);
  readonly openHistory = signal<number | null>(null);
  /** Roadmap V2 R2-D1: interest surcharge on new loans while a vanilla loan taken on top is open. */
  readonly surcharge = signal(0);
  readonly stateBadge = STATE_BADGE;

  readonly form = inject(FormBuilder).nonNullable.group({
    amount: [50000, [Validators.required, Validators.min(1)]],
    purpose: ['', [Validators.required, Validators.maxLength(200)]],
    termMonths: [36, [Validators.required, Validators.min(1), Validators.max(600)]],
  });

  readonly formAmount = toSignal(this.form.controls.amount.valueChanges, { initialValue: this.form.controls.amount.value });
  readonly highlighted = computed(() => Number(this.application()) || null);
  readonly activeLoans = computed(() => (this.loans() ?? []).filter((l) => l.status === 'ACTIVE'));
  readonly debt = computed(() => this.activeLoans().reduce((s, l) => s + l.remainingAmount, 0));
  readonly monthly = computed(() => this.activeLoans().reduce((s, l) => s + l.monthlyInstallment, 0));
  readonly blocked = computed(() => (this.loans() ?? []).some((l) => l.blocksNewCredit));
  readonly now = computed(() => this.store.savegame()?.gameTime ?? 0);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      untracked(() => this.load());
    });
  }

  load(): void {
    this.api.bypassSettings().subscribe({ next: (b) => this.surcharge.set(b.interestSurchargePercent), error: () => this.surcharge.set(0) });
    forkJoin({ apps: this.api.creditApplications(), loans: this.api.loans() }).subscribe({
      next: ({ apps, loans }) => {
        this.applications.set(apps);
        this.loans.set(loans);
        this.error.set(null);
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  state(a: CreditApplicationView): ApplicationState {
    return applicationState(a);
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.submitting.set(true);
    this.formError.set(null);
    this.api.applyForCredit(Number(v.amount), v.purpose.trim(), Number(v.termMonths), this.collateralIds()).subscribe({
      next: (a) => {
        this.submitting.set(false);
        this.applications.update((list) => [a, ...(list ?? [])]);
        this.form.reset();
        this.picker()?.reset();
      },
      error: (e) => {
        this.submitting.set(false);
        this.formError.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  acceptCounter(a: CreditApplicationView): void {
    this.api.acceptCounterOffer(a.id).subscribe({
      next: (updated) => {
        this.replace(updated);
        this.load();
        this.store.refresh();
      },
      error: (e) => this.setActionError(a.id, e),
    });
  }

  declineCounter(a: CreditApplicationView): void {
    this.api.declineCounterOffer(a.id).subscribe({ next: (u) => this.replace(u), error: (e) => this.setActionError(a.id, e) });
  }

  setDeferralText(loanId: number, text: string): void {
    this.deferralText.update((m) => ({ ...m, [loanId]: text }));
  }

  requestDeferral(l: LoanView): void {
    this.api.requestDeferral(l.id, (this.deferralText()[l.id] ?? '').trim()).subscribe({
      next: (r) => {
        this.deferralResult.update((m) => ({ ...m, [l.id]: r }));
        this.load();
      },
      error: (e) => this.setActionError(l.id, e),
    });
  }

  setSpecialAmount(loanId: number, value: string): void {
    const n = Math.floor(Number(value));
    this.specialAmount.update((m) => ({ ...m, [loanId]: value === '' || !Number.isFinite(n) ? null : n }));
  }

  /** Fills in the complete remaining debt (the pro-rata interest of the month comes on top). */
  payOffCompletely(l: LoanView): void {
    this.specialAmount.update((m) => ({ ...m, [l.id]: l.remainingAmount }));
  }

  /** What the Sondertilgung will debit: fee above the free amount of this year, interest only on full repayment. */
  specialPreview(l: LoanView): { amount: number; fee: number; interest: number; total: number } | null {
    const amount = this.specialAmount()[l.id];
    if (amount == null || amount < 1 || amount > l.remainingAmount) return null;
    const t = l.specialRepayment;
    const fee = Math.round((Math.max(0, amount - t.freeAmountLeft) * t.feeRatePercent) / 100);
    const interest = amount === l.remainingAmount ? t.payoffInterest : 0;
    return { amount, fee, interest, total: amount + fee + interest };
  }

  makeSpecialRepayment(l: LoanView): void {
    const preview = this.specialPreview(l);
    if (!preview) return;
    this.specialBusy.set(l.id);
    this.actionError.update((m) => ({ ...m, [l.id]: '' }));
    this.api.specialRepayment(l.id, preview.amount).subscribe({
      next: (r) => {
        this.specialBusy.set(null);
        this.specialResult.update((m) => ({ ...m, [l.id]: r }));
        this.specialAmount.update((m) => ({ ...m, [l.id]: null }));
        this.load();
        this.store.refresh();
      },
      error: (e) => {
        this.specialBusy.set(null);
        this.setActionError(l.id, e);
      },
    });
  }

  /** R3-K1: the bank's consent to sell a pledged field (the proceeds repay the collateral value). */
  requestSaleConsent(l: LoanView, c: CollateralView): void {
    this.consentBusy.set(c.farmlandId);
    this.api.requestSaleConsent(c.farmlandId).subscribe({
      next: () => {
        this.consentBusy.set(null);
        this.load();
      },
      error: (e) => {
        this.consentBusy.set(null);
        this.setActionError(l.id, e);
      },
    });
  }

  pledged(l: LoanView): CollateralView[] {
    return (l.collateral ?? []).filter((c) => c.status === 'PLEDGED');
  }

  toggleHistory(id: number): void {
    this.openHistory.update((v) => (v === id ? null : id));
  }

  remainingInstallments(l: LoanView): number {
    return l.remainingInstallments;
  }

  progress(l: LoanView): number {
    return l.principal > 0 ? Math.min(100, Math.max(0, ((l.principal - l.remainingAmount) / l.principal) * 100)) : 100;
  }

  private replace(a: CreditApplicationView): void {
    this.applications.update((list) => (list ?? []).map((x) => (x.id === a.id ? a : x)));
  }

  private setActionError(id: number, e: unknown): void {
    this.actionError.update((m) => ({ ...m, [id]: apiErrorMessage(e, this.i18n.t('common.error')) }));
  }
}
