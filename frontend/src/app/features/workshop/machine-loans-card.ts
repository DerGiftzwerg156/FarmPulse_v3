import { Component, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { LoanChoicesView, MachineLoanView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/** Running loans (delivering, on the farm, being returned, purchase offer open). */
export const RUNNING_LOAN = ['DELIVERING', 'ACTIVE', 'RETURNING', 'PURCHASE_OFFER'];

/** End reason of a loan as label key: "LATE:VEHICLE_IN_USE" counts as LATE. */
export function loanEndKey(reason: string | null): string | null {
  if (!reason) return null;
  return reason.startsWith('LATE:') ? 'LATE' : reason;
}

/**
 * Roadmap V3.1 R31-A2 in the app "Werkstatt": "Vorführung anfragen" (a new machine of the shop catalogue, free for
 * 1-2 game days, then a purchase offer) and all borrowed and demo machines with rent, late days and compensation.
 */
@Component({
  selector: 'app-machine-loans-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Badge, Button, Card],
  template: `
    <app-card [title]="'workshop.loans.title' | t" data-testid="machine-loans">
      <p class="mb-3 text-[12px] text-muted">{{ 'workshop.loans.intro' | t }}</p>
      @if (demo(); as d) {
        @if (d.choices.length) {
          <form class="flex flex-wrap items-end gap-2" (submit)="$event.preventDefault(); requestDemo()" data-testid="demo-form">
            <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'workshop.loans.demoMachine' | t }}
              <select class="fp-input w-64" [value]="choice() ?? d.choices[0].storeXmlFilename" (change)="choice.set($any($event.target).value)" data-testid="demo-choice">
                @for (c of d.choices; track c.storeXmlFilename) {
                  <option [value]="c.storeXmlFilename">{{ c.name }} ({{ c.listPrice | money }})</option>
                }
              </select>
            </label>
            <app-button type="submit" variant="secondary" [disabled]="busy()" data-testid="demo-request">{{ 'workshop.loans.demoRequest' | t }}</app-button>
            <p class="w-full text-[11px] text-muted">{{ 'workshop.loans.demoHint' | t: { min: d.daysMin, max: d.daysMax } }}</p>
          </form>
        } @else {
          <p class="text-[12px] text-muted" data-testid="no-demo">{{ 'workshop.loans.noDemo' | t }}</p>
        }
      }
      @if (info(); as i) { <p class="mt-2 text-[12px] text-accent" data-testid="loan-info">{{ i }}</p> }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="loan-error">{{ e }}</p> }

      <ul class="mt-3 space-y-2" data-testid="loan-list">
        @for (l of loans(); track l.id) {
          <li class="rounded-xl border border-border bg-bg px-3 py-2 text-[12px]" data-testid="loan" [attr.data-status]="l.status">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="font-display text-[12px] font-bold text-text">{{ l.vehicleName }}</span>
              <app-badge [variant]="running(l) ? 'positive' : 'neutral'">{{ l.kind | label: 'machineLoanKind' }} · {{ l.status | label: 'machineLoanStatus' }}</app-badge>
            </div>
            <div class="mt-1 text-muted">
              {{ l.lender?.name ?? '–' }}
              @if (l.kind === 'LOAN') { · {{ 'workshop.loans.rent' | t: { rent: (l.dailyRent | money), days: l.days } }} }
              @if (l.endsGameTime !== null && running(l)) { · {{ 'workshop.loans.until' | t: { time: (l.endsGameTime | gameTime) } }} }
              @if (l.lateDays > 0) { · <span class="text-warn">{{ 'workshop.loans.late' | t: { days: l.lateDays } }}</span> }
              @if ((l.compensation ?? 0) > 0) { · <span class="text-danger">{{ 'workshop.loans.compensation' | t: { amount: (l.compensation | money) } }}</span> }
              @if (endKey(l); as k) { · {{ k | label: 'machineLoanEnd' }} }
            </div>
            @if (l.status === 'PURCHASE_OFFER') {
              <p class="mt-1 text-[11px] text-muted">{{ 'workshop.loans.purchaseHint' | t }}</p>
            }
          </li>
        } @empty {
          <li class="text-[12px] text-muted">{{ 'workshop.loans.none' | t }}</li>
        }
      </ul>
    </app-card>
  `,
})
export class MachineLoansCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly loans = signal<MachineLoanView[]>([]);
  readonly demo = signal<LoanChoicesView | null>(null);
  readonly choice = signal<string | null>(null);
  readonly busy = signal(false);
  readonly info = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.store.mailVersion();
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.machineLoans().subscribe({ next: (l) => this.loans.set(l), error: () => this.loans.set([]) });
    this.api.demoChoices().subscribe({ next: (d) => this.demo.set(d), error: () => this.demo.set(null) });
  }

  running(l: MachineLoanView): boolean {
    return RUNNING_LOAN.includes(l.status);
  }

  endKey(l: MachineLoanView): string | null {
    return this.running(l) ? null : loanEndKey(l.endReason);
  }

  requestDemo(): void {
    const d = this.demo();
    const xml = this.choice() ?? d?.choices[0]?.storeXmlFilename;
    if (!xml) return;
    this.busy.set(true);
    this.info.set(null);
    this.error.set(null);
    this.api.requestDemo(xml).subscribe({
      next: (l) => {
        this.busy.set(false);
        this.info.set(this.i18n.t('workshop.loans.demoRequested', { vehicle: l.vehicleName }));
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
