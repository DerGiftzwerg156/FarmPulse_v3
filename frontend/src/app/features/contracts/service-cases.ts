import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CaseView, ContractView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Card } from '../../shared/ui/card';
import { CaseCard } from './case-card';
import { ContractCard } from './contract-card';

/** Cases still shown as open: waiting for an answer, or accepted and running (trader offer, inspection). */
export const OPEN_CASE = ['AWAITING_PLAYER', 'IN_PROGRESS'];
const ROLEPLAY_CASES = ['TAX_BILL', 'AUTHORITY_INSPECTION', 'SPONSORING_REQUEST', 'INVITATION'];

/**
 * The service cases and contracts of some kinds, as one section of an app (e.g. damages in "Versicherung", lease and
 * compensation claims in "Flurkarte"): open cases with their actions, the contracts and the history.
 * Contracts and cases are reloaded on live events; `?case=` / `?contract=` highlight an entry.
 */
@Component({
  selector: 'app-service-cases',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Card, CaseCard, ContractCard],
  template: `
    <div class="flex flex-col gap-4" [attr.data-testid]="testId()">
      @if (openCases().length || showEmpty()) {
        <app-card [title]="openTitle() | t" data-testid="open-cases">
          <ng-content select="[open-intro]" />
          <ul class="space-y-2">
            @for (c of openCases(); track c.id) {
              <li><app-case-card [c]="c" [highlight]="highlightCase() === c.id" (changed)="changed()" /></li>
            } @empty {
              <li class="text-sm text-muted">{{ 'contracts.noOpenCases' | t }}</li>
            }
          </ul>
        </app-card>
      }
      @if (contractKinds().length) {
        <app-card [title]="contractsTitle() | t" data-testid="contracts">
          <ng-content select="[contracts-intro]" />
          <ul class="space-y-2">
            @for (c of contracts(); track c.id) {
              <li><app-contract-card [contract]="c" [highlight]="highlightContract() === c.id" (changed)="changed()" /></li>
            } @empty {
              <li class="text-sm text-muted">{{ 'contracts.noContracts' | t }}</li>
            }
          </ul>
        </app-card>
      }
      @if (history()) {
        <app-card [title]="'contracts.history' | t" data-testid="case-history">
          <ul class="space-y-1">
            @for (c of closedCases(); track c.id) {
              <li class="flex flex-wrap items-center justify-between gap-2 border-t border-border py-1.5 text-[12px]" data-testid="closed-case">
                <span class="text-text">{{ c.kind | label: 'caseKind' }}@if (c.farmlandId !== null) { · {{ 'contracts.field' | t: { id: c.farmlandId } }} }
                  @if (c.damageAmount !== null) { · {{ 'contracts.damage' | t: { amount: (c.damageAmount | money) } }} }
                  @if (c.kind === 'MISSION_REFERRAL' && c.title) { · {{ c.title }} }
                  @if (c.kind === 'REPAIR' && c.reference) { · {{ 'contracts.vehicle' | t: { id: c.reference, condition: c.quantity } }} }
                  @if (isRoleplayCase(c)) {
                    @if (c.kind === 'TAX_BILL') { · {{ c.title ?? (c.reference | label: 'taxBill') }} }
                    @if (c.kind === 'AUTHORITY_INSPECTION') { · {{ c.title | label: 'authorityRule' }} }
                    @if (c.kind === 'SPONSORING_REQUEST') { · {{ c.reference | label: 'club' }} }
                    @if (c.kind === 'INVITATION') { · {{ c.reference | label: 'festival' }} }
                    @if (c.kind === 'AUTHORITY_INSPECTION' && c.costAmount) { · {{ 'contracts.fine' | t: { amount: (c.costAmount | money) } }} }
                    @if (c.kind !== 'AUTHORITY_INSPECTION' && c.payoutAmount !== null) { · {{ 'contracts.paid' | t: { amount: (c.payoutAmount | money) } }} }
                  } @else {
                    @if (c.kind !== 'REPAIR' && c.quantity && c.reference) { · {{ 'contracts.animals' | t: { quantity: c.quantity, animal: (c.reference | label: 'animalType') } }} }
                    @if (c.costAmount !== null) { · {{ 'contracts.invoice' | t: { amount: (c.costAmount | money) } }} }
                    @if (c.payoutAmount !== null) { · {{ 'contracts.payout' | t: { amount: (c.payoutAmount | money) } }} }
                  }</span>
                <span class="fp-label">{{ c.resolution | label: 'caseResolution' }} · {{ c.gameTime | gameTime }}</span>
              </li>
            } @empty {
              <li class="text-sm text-muted">{{ 'contracts.noHistory' | t }}</li>
            }
          </ul>
        </app-card>
      }
    </div>
  `,
})
export class ServiceCases {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly tasks = inject(TasksStore);

  readonly caseKinds = input<string[]>([]);
  readonly contractKinds = input<string[]>([]);
  /** Title of the open-cases card (i18n key) and whether it shows when empty. */
  readonly openTitle = input('contracts.openCases');
  readonly contractsTitle = input('contracts.contractsTitle');
  readonly showEmpty = input(true);
  readonly history = input(true);
  readonly highlightCase = input<number | null>(null);
  readonly highlightContract = input<number | null>(null);
  readonly testId = input('service-cases');

  private readonly allCases = signal<CaseView[]>([]);
  private readonly allContracts = signal<ContractView[]>([]);

  readonly openCases = computed(() => this.allCases().filter((c) => this.caseKinds().includes(c.kind) && OPEN_CASE.includes(c.status)));
  readonly closedCases = computed(() => this.allCases().filter((c) => this.caseKinds().includes(c.kind) && !OPEN_CASE.includes(c.status)));
  readonly contracts = computed(() => this.allContracts().filter((c) => this.contractKinds().includes(c.kind)));

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
        this.allContracts.set(r.contracts);
        this.allCases.set(r.cases);
      },
      error: () => {
        this.allContracts.set([]);
        this.allCases.set([]);
      },
    });
  }

  changed(): void {
    this.load();
    this.tasks.reload();
    this.store.refresh();
  }

  isRoleplayCase(c: CaseView): boolean {
    return ROLEPLAY_CASES.includes(c.kind);
  }
}
