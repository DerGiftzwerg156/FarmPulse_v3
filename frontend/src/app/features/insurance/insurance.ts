import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Observable } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { ContractView, InsuranceQuoteView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';
import { ServiceCases } from '../contracts/service-cases';

/**
 * Hof-Tablet app "Versicherung" (TODO T-20): storm and hail insurance (tariffs, offer, cancel) and the damages -
 * storm, hail and wildlife - with report, counter demand and joint measure.
 */
@Component({
  selector: 'app-insurance',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Card, Badge, Button, PageErrorView, ServiceCases],
  templateUrl: './insurance.html',
})
export class Insurance {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly tasks = inject(TasksStore);
  private readonly i18n = inject(TranslationService);

  /** `?contract=` / `?case=` highlight an entry (links from mails and "Aufgaben"). */
  readonly contract = input<string>();
  readonly case = input<string>();

  readonly damageKinds = ['STORM_DAMAGE', 'HAIL_DAMAGE', 'WILDLIFE_DAMAGE'];
  readonly contracts = signal<ContractView[] | null>(null);
  readonly quotes = signal<InsuranceQuoteView[]>([]);
  readonly error = signal<PageError | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly busy = signal(false);

  readonly insurance = computed(() => (this.contracts() ?? []).filter((c) => c.kind === 'INSURANCE'));
  readonly activeInsurance = computed(() => this.insurance().find((c) => c.status === 'ACTIVE') ?? null);
  readonly insuranceOffers = computed(() => this.insurance().filter((c) => c.status === 'OFFERED'));
  readonly highlightedContract = computed(() => Number(this.contract()) || null);
  readonly highlightedCase = computed(() => Number(this.case()) || null);

  constructor() {
    effect(() => {
      this.store.mailVersion();
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.contracts().subscribe({
      next: (c) => {
        this.contracts.set(c);
        this.error.set(null);
        if (!c.some((x) => x.kind === 'INSURANCE' && x.status === 'ACTIVE')) this.loadQuotes();
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
        this.tasks.reload();
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

  contractAction(c: ContractView, action: string): void {
    this.run(this.api.contractAction(c.id, action));
  }
}
