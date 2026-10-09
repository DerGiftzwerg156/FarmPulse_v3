import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { TaskView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';
import { CaseCard } from '../contracts/case-card';
import { toneClass } from '../../layout/apps';
import { taskApp, taskLink } from '../../layout/task-apps';
import { dueLabel, isUrgent } from './task-groups';

/**
 * One open decision in "Aufgaben": app of origin, deadline and the same actions as in the app itself (they call the
 * existing endpoints). Numbers are only entered in form fields; negotiations and applications open in their app.
 */
@Component({
  selector: 'app-task-card',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, Button, Icon, CaseCard],
  template: `
    @let t = task();
    <article class="flex h-full flex-col gap-1.5 rounded-2xl border bg-surface px-4 py-3" [class.border-warn/50]="urgent()" [class.border-border]="!urgent()"
      data-testid="task" [attr.data-type]="t.type" [attr.data-kind]="t.kind">
      <header class="flex items-center justify-between gap-2">
        <span class="flex min-w-0 items-center gap-2 font-display text-[10px] font-semibold uppercase tracking-[0.16em] text-[#8FA39A]">
          <app-icon [name]="app().icon" size="h-[15px] w-[15px]" [class]="tone()" />
          <span class="truncate" data-testid="task-app">{{ app().label | t }}</span>
        </span>
        <span class="shrink-0 rounded-md border px-1.5 font-display text-[10px] font-bold tracking-[0.08em]"
          [class.border-warn]="urgent()" [class.text-warn]="urgent()" [class.border-border]="!urgent()" [class.text-muted]="!urgent()"
          data-testid="task-due">{{ due() }}</span>
      </header>

      @switch (t.type) {
        @case ('CASE') {
          <app-case-card [c]="t.serviceCase!" [framed]="false" (changed)="changed.emit()" />
        }
        @case ('CONTRACT_OFFER') {
          @let c = t.contract!;
          <div class="text-[15px] font-semibold text-text">{{ 'tasks.contractOffer' | t: { kind: (c.kind | label: 'contractKind') } }}</div>
          <div class="text-[12px] text-[#8FA39A]">
            {{ c.character?.name }}
            @if (c.level) { · {{ c.level | label: 'insuranceLevel' }} }
            @if (c.farmlandId !== null) { · {{ 'contracts.field' | t: { id: c.farmlandId } }} }
            · {{ 'contracts.perMonth' | t: { amount: (c.monthlyAmount | money) } }}
            @if (c.coveragePercent !== null) { · {{ 'contracts.coverage' | t: { percent: c.coveragePercent, deductible: (c.deductible | money) } }} }
          </div>
          <div class="mt-auto flex flex-wrap gap-1.5 pt-1">
            <app-button [disabled]="busy()" (pressed)="run(api.contractAction(c.id, 'accept'))" data-testid="task-accept">{{ 'contracts.accept' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="run(api.contractAction(c.id, 'decline'))" data-testid="task-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        }
        @case ('LEASE_RENEWAL') {
          @let c = t.contract!;
          <div class="text-[15px] font-semibold text-text">{{ 'tasks.leaseEnds' | t: { id: c.farmlandId } }}</div>
          <div class="text-[12px] text-[#8FA39A]">{{ c.character?.name }} · {{ (c.kind === 'LEASE_OUT' ? 'tasks.leaseOutEndsHint' : 'tasks.leaseEndsHint') | t }}</div>
          <div class="mt-auto flex flex-wrap gap-1.5 pt-1">
            @if (c.renewalAmount) {
              <app-button [disabled]="busy()" (pressed)="run(api.contractAction(c.id, 'renew'))" data-testid="task-renew">{{ (c.kind === 'LEASE_OUT' ? 'contracts.renewLeaseOut' : 'contracts.renew') | t: { amount: (c.renewalAmount | money) } }}</app-button>
            }
            @if (c.purchasePrice) {
              <app-button variant="secondary" [disabled]="busy()" (pressed)="run(api.contractAction(c.id, 'buy'))" data-testid="task-buy">{{ 'contracts.buy' | t: { amount: (c.purchasePrice | money) } }}</app-button>
            }
          </div>
        }
        @case ('CREDIT_COUNTER') {
          @let a = t.application!;
          <div class="text-[15px] font-semibold text-text">{{ 'tasks.counterOffer' | t: { offered: (a.offeredAmount | money), amount: (a.amount | money) } }}</div>
          <div class="text-[12px] text-[#8FA39A]">{{ 'tasks.counterOfferDetail' | t: { months: a.offeredTermMonths, rate: a.offeredInterestRatePercent } }} · {{ a.purpose }}</div>
          <div class="mt-auto flex flex-wrap gap-1.5 pt-1">
            <app-button [disabled]="busy()" (pressed)="run(api.acceptCounterOffer(a.id))" data-testid="task-accept">{{ 'contracts.accept' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="run(api.declineCounterOffer(a.id))" data-testid="task-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        }
        @case ('CALL') {
          @let m = t.call!;
          <div class="text-[15px] font-semibold text-text">{{ 'tasks.calling' | t: { name: m.character?.name ?? ('mailbox.system' | t) } }}</div>
          <div class="text-[12px] text-[#8FA39A]">{{ m.subject }} · {{ 'calls.declineHint' | t }}</div>
          <div class="mt-auto flex flex-wrap gap-1.5 pt-1">
            <app-button [disabled]="busy()" (pressed)="acceptCall(m.id)" data-testid="task-accept">{{ 'calls.accept' | t }}</app-button>
            <app-button variant="danger" [disabled]="busy()" (pressed)="run(api.declineCall(m.id))" data-testid="task-decline">{{ 'calls.decline' | t }}</app-button>
          </div>
        }
        @case ('NEGOTIATION') {
          @let n = t.negotiation!;
          <div class="text-[15px] font-semibold text-text">{{ n.kind | label: 'negotiationKind' }} · @if (n.assetType === 'VEHICLE') { {{ 'workshop.machine' | t }} } @else { {{ 'farmland.fieldLabel' | t: { id: n.assetId } }} }</div>
          <div class="text-[12px] text-[#8FA39A]">
            {{ n.counterpart?.name ?? n.announcer?.name }} · {{ 'tasks.round' | t: { used: n.roundsUsed, max: n.maxRounds } }}
            @if (n.lastCounterOffer) { · {{ 'tasks.lastCounter' | t: { amount: (n.lastCounterOffer | money) } }} }
          </div>
        }
        @case ('MARKET_OFFER') {
          @let e = t.marketEvent!;
          <div class="text-[15px] font-semibold text-text">{{ e.eventType | label: 'marketEventType' }}@if (e.fillType) { · {{ e.fillType | label: 'fillType' }} }</div>
          <div class="text-[12px] text-[#8FA39A]">
            {{ e.character?.name }}
            @if (e.fixedPrice) { · {{ 'tasks.fixedPrice' | t: { price: (e.fixedPrice | money) } }} }
            @if (e.maxQuantity) { · {{ 'tasks.maxQuantity' | t: { quantity: (e.maxQuantity | num) } }} }
          </div>
          <div class="mt-auto flex flex-wrap gap-1.5 pt-1">
            <app-button [disabled]="busy()" (pressed)="run(api.participate(e.id, true))" data-testid="task-accept">{{ 'market.participate' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="run(api.participate(e.id, false))" data-testid="task-decline">{{ 'market.decline' | t }}</app-button>
          </div>
        }
        @case ('INVESTOR_DUE') {
          <!-- Roadmap V3.2 R32-I4: a delivery still due to an investor in the current period -->
          @let d = t.investorDue!;
          <div class="text-[15px] font-semibold text-text">{{ 'tasks.investorDue' | t: { name: d.investor ?? '–' } }}</div>
          <div class="text-[12px] text-[#8FA39A]" data-testid="investor-due">{{ 'investors.due' | t: { remaining: (d.remaining | num), unit: (d.type === 'A1' ? ('investors.animals' | t) : 'l'), what: ((d.subType ?? d.fillType ?? '') | label: 'fillType') } }}</div>
        }
        @case ('POSTING') {
          <div class="text-[15px] font-semibold text-text">{{ 'tasks.applicants' | t: { role: (t.kind | label: 'jobRole'), n: t.pendingApplicants } }}</div>
          <div class="text-[12px] text-[#8FA39A]">{{ 'tasks.applicantsHint' | t }}</div>
        }
      }

      @if (error(); as err) {
        <p class="text-[12px] text-danger" data-testid="task-error">{{ err }}</p>
      }
      <a [routerLink]="link().path" [queryParams]="link().query" class="mt-auto self-end pt-1 font-display text-[10px] uppercase tracking-[0.12em] text-accent hover:underline"
        data-testid="task-open">{{ 'tasks.openInApp' | t }} →</a>
    </article>
  `,
})
export class TaskCard {
  readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly router = inject(Router);

  readonly task = input.required<TaskView>();
  /** Current game time (deadline label and urgency). */
  readonly now = input.required<number>();
  readonly changed = output<void>();

  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly app = computed(() => taskApp(this.task()));
  readonly tone = computed(() => toneClass(this.app().tone));
  readonly link = computed(() => taskLink(this.task()));
  readonly urgent = computed(() => isUrgent(this.task(), this.now()));
  readonly due = computed(() => dueLabel(this.task(), this.now(), this.i18n));

  run(o: Observable<unknown>, after?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    o.subscribe({
      next: () => {
        this.busy.set(false);
        after?.();
        this.changed.emit();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  /** An accepted call continues in the phone app. */
  acceptCall(id: number): void {
    this.run(this.api.acceptCall(id), () => this.router.navigate(['/calls'], { queryParams: { id } }));
  }
}
