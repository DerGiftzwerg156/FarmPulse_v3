import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { ContractView, EmployeeView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { ServiceCases } from '../contracts/service-cases';

/**
 * Hof-Tablet app "Werkstatt" (TODO T-22, Roadmap V2 R2-A6): the maintenance contract with the workshop, the repairs
 * it did in the game and the farm's own mechanics.
 */
@Component({
  selector: 'app-workshop',
  imports: [RouterLink, TranslatePipe, Button, Card, ServiceCases],
  template: `
    <section class="grid grid-cols-1 gap-4 xl:grid-cols-2">
      <div class="flex flex-col gap-4">
        <app-card [title]="'workshop.maintenance' | t" data-testid="maintenance">
          <p class="mb-3 text-[12px] text-muted">{{ 'contracts.maintenanceHint' | t }}</p>
          @if (!hasMaintenance()) {
            <app-button variant="secondary" [disabled]="busy()" (pressed)="requestMaintenance()" data-testid="request-maintenance">{{ 'contracts.requestMaintenance' | t }}</app-button>
          }
          @if (error(); as e) {
            <p class="mt-2 text-[12px] text-danger">{{ e }}</p>
          }
        </app-card>
        <app-card [title]="'workshop.mechanics' | t" data-testid="mechanics">
          <ul class="space-y-2">
            @for (m of mechanics(); track m.id) {
              <li class="flex items-center justify-between gap-2 rounded-xl border border-border bg-bg px-3 py-2 text-[13px]" data-testid="mechanic">
                <span class="text-text">{{ m.character.name }}</span>
                <span class="fp-label">{{ 'employees.skill' | t }} {{ m.skill }}</span>
              </li>
            } @empty {
              <li class="text-[13px] text-muted">{{ 'workshop.noMechanic' | t }}
                <a routerLink="/employees" class="text-accent hover:underline">{{ 'workshop.toStaff' | t }}</a></li>
            }
          </ul>
          <p class="mt-3 text-[12px] text-muted">{{ 'workshop.reports' | t }} <a routerLink="/mailbox" class="text-accent hover:underline">{{ 'nav.mailbox' | t }}</a></p>
        </app-card>
      </div>
      <app-service-cases [caseKinds]="['REPAIR']" [contractKinds]="['MAINTENANCE']" contractsTitle="workshop.contracts" [showEmpty]="false"
        [highlightCase]="highlightedCase()" [highlightContract]="highlightedContract()" testId="workshop-cases" />
    </section>
  `,
})
export class Workshop {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly tasks = inject(TasksStore);
  private readonly i18n = inject(TranslationService);

  readonly contract = input<string>();
  readonly case = input<string>();
  readonly contracts = signal<ContractView[]>([]);
  readonly employees = signal<EmployeeView[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly hasMaintenance = computed(() =>
    this.contracts().some((c) => c.kind === 'MAINTENANCE' && (c.status === 'ACTIVE' || c.status === 'OFFERED')),
  );
  readonly mechanics = computed(() => this.employees().filter((e) => e.jobRole === 'MECHANIC' && e.status === 'ACTIVE'));
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
    this.api.contracts().subscribe({ next: (c) => this.contracts.set(c), error: () => this.contracts.set([]) });
    this.api.employees().subscribe({ next: (e) => this.employees.set(e), error: () => this.employees.set([]) });
  }

  requestMaintenance(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.requestMaintenanceOffer().subscribe({
      next: () => {
        this.busy.set(false);
        this.store.stateVersion.update((v) => v + 1);
        this.tasks.reload();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
