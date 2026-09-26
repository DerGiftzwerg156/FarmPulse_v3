import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Observable, forkJoin } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CaseView, ContractView, InsuranceQuoteView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';

export const CONTRACT_BADGE: Record<string, BadgeVariant> = {
  OFFERED: 'warning',
  ACTIVE: 'positive',
  DECLINED: 'neutral',
  CANCELLED: 'neutral',
  ENDED: 'neutral',
};

/**
 * Contracts & service cases (TODO T-20 / T-22): insurance (offer, accept, cancel), damage reports, and the cases and
 * contracts of the other service characters. All amounts come from the backend formulas.
 */
@Component({
  selector: 'app-contracts',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, GameTimePipe, Card, Badge, Button, PageErrorView],
  templateUrl: './contracts.html',
})
export class Contracts {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?contract=` / `?case=` highlight an entry (links from mails). */
  readonly contract = input<string>();
  readonly case = input<string>();

  readonly contracts = signal<ContractView[] | null>(null);
  readonly cases = signal<CaseView[] | null>(null);
  readonly quotes = signal<InsuranceQuoteView[]>([]);
  readonly error = signal<PageError | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly busy = signal(false);

  readonly insurance = computed(() => (this.contracts() ?? []).filter((c) => c.kind === 'INSURANCE'));
  readonly activeInsurance = computed(() => this.insurance().find((c) => c.status === 'ACTIVE') ?? null);
  readonly insuranceOffers = computed(() => this.insurance().filter((c) => c.status === 'OFFERED'));
  readonly otherContracts = computed(() => (this.contracts() ?? []).filter((c) => c.kind !== 'INSURANCE'));
  readonly openCases = computed(() => (this.cases() ?? []).filter((c) => c.status === 'AWAITING_PLAYER'));
  readonly closedCases = computed(() => (this.cases() ?? []).filter((c) => c.status !== 'AWAITING_PLAYER'));
  readonly highlightedContract = computed(() => Number(this.contract()) || null);
  readonly highlightedCase = computed(() => Number(this.case()) || null);

  constructor() {
    effect(() => {
      this.store.mailVersion();
      this.store.callVersion();
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    forkJoin({ contracts: this.api.contracts(), cases: this.api.cases() }).subscribe({
      next: (r) => {
        this.contracts.set(r.contracts);
        this.cases.set(r.cases);
        this.error.set(null);
        if (!r.contracts.some((c) => c.kind === 'INSURANCE' && c.status === 'ACTIVE')) this.loadQuotes();
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  private loadQuotes(): void {
    this.api.insuranceQuotes().subscribe({ next: (q) => this.quotes.set(q), error: () => this.quotes.set([]) });
  }

  private run(o: Observable<unknown>): void {
    this.busy.set(true);
    this.actionError.set(null);
    o.subscribe({
      next: () => {
        this.busy.set(false);
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  requestOffer(level: string): void {
    this.run(this.api.requestInsuranceOffer(level));
  }

  contractAction(c: ContractView, action: string, body: unknown = {}): void {
    this.run(this.api.contractAction(c.id, action, body));
  }

  caseAction(c: CaseView, action: string, body: unknown = {}): void {
    this.run(this.api.caseAction(c.id, action, body));
  }

  isInsuranceCase(c: CaseView): boolean {
    return c.kind === 'STORM_DAMAGE' || c.kind === 'HAIL_DAMAGE';
  }

  badge(status: string): BadgeVariant {
    return CONTRACT_BADGE[status] ?? 'neutral';
  }
}
