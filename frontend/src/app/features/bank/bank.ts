import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CreditApplicationView, DeferralView, LoanView } from '../../core/api/models';
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
 * repayment plan/history and deferral requests.
 */
@Component({
  selector: 'app-bank',
  imports: [ReactiveFormsModule, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Card, Badge, Button, Stat, PageErrorView],
  templateUrl: './bank.html',
})
export class Bank {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?application=` highlights an application (link from the bank's mail). */
  readonly application = input<string>();

  readonly applications = signal<CreditApplicationView[] | null>(null);
  readonly loans = signal<LoanView[] | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly formError = signal<string | null>(null);
  readonly submitting = signal(false);
  readonly actionError = signal<Record<number, string>>({});
  readonly deferralText = signal<Record<number, string>>({});
  readonly deferralResult = signal<Record<number, DeferralView>>({});
  readonly openHistory = signal<number | null>(null);
  readonly stateBadge = STATE_BADGE;

  readonly form = inject(FormBuilder).nonNullable.group({
    amount: [50000, [Validators.required, Validators.min(1)]],
    purpose: ['', [Validators.required, Validators.maxLength(200)]],
    termMonths: [36, [Validators.required, Validators.min(1), Validators.max(600)]],
  });

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
    this.api.applyForCredit(Number(v.amount), v.purpose.trim(), Number(v.termMonths)).subscribe({
      next: (a) => {
        this.submitting.set(false);
        this.applications.update((list) => [a, ...(list ?? [])]);
        this.form.reset();
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

  toggleHistory(id: number): void {
    this.openHistory.update((v) => (v === id ? null : id));
  }

  remainingInstallments(l: LoanView): number {
    return Math.max(0, l.termMonths - l.paidInstallments);
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
