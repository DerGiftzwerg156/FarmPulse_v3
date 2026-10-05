import { Component, inject, input, output, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CaseView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';

/**
 * One open service case with its actions (accept, counter, sponsor, pay, RSVP, report ...). Used wherever the case
 * shows up - in its app and in "Aufgaben". Amounts only via form fields; emits `changed` after a successful action.
 */
@Component({
  selector: 'app-case-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Badge, Button],
  template: `
    <div data-testid="case" [attr.data-kind]="c().kind" [class]="framed() ? 'rounded-xl border border-warn/50 bg-bg p-3' : ''" [class.ring-1]="highlight()">

      <div class="flex items-center justify-between gap-2">
        <span class="font-display text-[12px] font-bold text-text">{{ c().kind | label: 'caseKind' }}@if (c().farmlandId !== null) { · {{ 'contracts.field' | t: { id: c().farmlandId } }} }</span>
        <span class="fp-label">{{ c().character?.name }}</span>
      </div>
      <div class="mt-1 text-[12px] text-muted">
        @if (c().damageAmount !== null) { {{ 'contracts.damage' | t: { amount: (c().damageAmount | money) } }} }
        @if (c().deadlineGameTime !== null) { @if (c().damageAmount !== null) { · } {{ 'contracts.deadline' | t: { time: (c().deadlineGameTime | gameTime) } }} }
      </div>
      @if (c().kind === 'WILDLIFE_DAMAGE') {
        <div class="mt-1 text-[12px] text-text" data-testid="wildlife-offer">{{ 'contracts.offer' | t: { amount: (c().offerAmount | money) } }}</div>
        <div class="mt-2 flex flex-wrap items-end gap-2">
          <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'contracts.accept' | t }}</app-button>
          <label class="space-y-1">
            <span class="fp-label">{{ 'contracts.demand' | t }}</span>
            <input class="fp-input w-32 font-mono" type="number" min="1" step="any" [value]="demand() ?? ''"
              (input)="setDemand($any($event.target).valueAsNumber)" data-testid="demand-input" />
          </label>
          <app-button variant="secondary" [disabled]="busy() || !demand()" (pressed)="act('counter', { amount: demand() })" data-testid="case-counter">{{ 'contracts.counter' | t }}</app-button>
          <app-button variant="secondary" [disabled]="busy()" (pressed)="act('measure')" data-testid="case-measure">{{ 'contracts.measure' | t: { amount: (c().measureCost ?? 0) | money } }}</app-button>
          <app-button variant="danger" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.dispute' | t }}</app-button>
        </div>
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.measureHint' | t }}</p>
      }
      @if (c().kind === 'MISSION_REFERRAL') {
        <div class="mt-1 text-[12px] text-text" data-testid="mission-referral">{{ 'contracts.mission' | t: { title: c().title ?? '–', amount: (c().offerAmount | money) } }}</div>
        @if (c().status === 'IN_PROGRESS') {
          <app-badge variant="positive">{{ c().status | label: 'caseStatus' }}</app-badge>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.missionHint' | t }}</p>
      }
      @if (c().kind === 'LIVESTOCK_OFFER') {
        <div class="mt-1 text-[12px] text-text" data-testid="livestock-offer">{{ 'contracts.livestockOffer' | t: { direction: (c().direction | label: 'tradeDirection'), quantity: c().quantity, animal: (c().reference | label: 'animalType'), amount: (c().offerAmount | money) } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'contracts.accept' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        } @else {
          <app-badge variant="positive">{{ c().status | label: 'caseStatus' }}</app-badge>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.livestockHint' | t }}</p>
      }
      @if (c().kind === 'COMPENSATION_CLAIM') {
        <div class="mt-1 text-[12px] text-text" data-testid="compensation-claim">{{ 'contracts.compensationClaim' | t: { name: c().character?.name ?? '–', amount: (c().offerAmount | money) } }}</div>
        <div class="mt-2 flex flex-wrap gap-2">
          <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'contracts.payCompensation' | t }}</app-button>
          <app-button variant="danger" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
        </div>
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.compensationHint' | t }}</p>
      }
      @if (c().kind === 'TAX_BILL') {
        <div class="mt-1 text-[12px] text-text" data-testid="tax-bill">{{ 'contracts.taxBill' | t: { title: c().title ?? (c().reference | label: 'taxBill'), amount: (c().offerAmount | money) } }}
          @if (c().costAmount) { · <span class="text-danger">{{ 'contracts.lateFees' | t: { amount: (c().costAmount | money) } }}</span> }</div>
        <div class="mt-2 flex flex-wrap gap-2">
          <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'contracts.payTax' | t: { amount: ((c().offerAmount ?? 0) + (c().costAmount ?? 0)) | money } }}</app-button>
        </div>
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.taxHint' | t }}</p>
      }
      @if (c().kind === 'GRANT_REPAYMENT' || c().kind === 'SOCIAL_INSURANCE_BILL') {
        <!-- Roadmap V3.1 R31-B2 / R31-B5: repayment of a grant, fee of the Berufsgenossenschaft - paid like a tax bill -->
        <div class="mt-1 text-[12px] text-text" data-testid="authority-bill">{{ 'contracts.authorityBill' | t: { title: c().title ?? '–', amount: (c().offerAmount | money) } }}
          @if (c().costAmount) { · <span class="text-danger">{{ 'contracts.lateFees' | t: { amount: (c().costAmount | money) } }}</span> }</div>
        @if (c().kind === 'SOCIAL_INSURANCE_BILL') {
          <div class="mt-1 text-[11px] text-muted" data-testid="social-insurance-basis">{{ 'authorities.socialInsurance.basis' | t: { hectares: c().hectares ?? 0, employees: c().baselineCount ?? 0 } }}</div>
        }
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'contracts.payTax' | t: { amount: ((c().offerAmount ?? 0) + (c().costAmount ?? 0)) | money } }}</app-button>
          </div>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.authorityBillHint' | t }}</p>
      }
      @if (c().kind === 'AUTHORITY_INSPECTION') {
        <div class="mt-1 text-[12px] text-text" data-testid="inspection">{{ 'contracts.inspection' | t: { rule: (c().title | label: 'authorityRule') } }}</div>
        @if (c().roundsUsed > 0) {
          <p class="mt-1 text-[12px] text-warn" data-testid="inspection-requirement">{{ 'contracts.inspectionRequirement' | t }}</p>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.inspectionHint' | t }}</p>
      }
      @if (c().kind === 'SPONSORING_REQUEST') {
        <div class="mt-1 text-[12px] text-text" data-testid="sponsoring">{{ 'contracts.sponsoring' | t: { club: (c().reference | label: 'club') } }}</div>
        <div class="mt-2 flex flex-wrap gap-2">
          @for (tier of c().tiers ?? []; track tier) {
            <app-button [disabled]="busy()" (pressed)="act('sponsor', { amount: tier })" data-testid="sponsor-tier">{{ 'contracts.sponsor' | t: { amount: (tier | money) } }}</app-button>
          }
          <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
        </div>
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.sponsoringHint' | t }}</p>
      }
      @if (c().kind === 'INVITATION') {
        <div class="mt-1 text-[12px] text-text" data-testid="invitation">{{ 'contracts.invitation' | t: { festival: (c().reference | label: 'festival') } }}</div>
        <div class="mt-2 flex flex-wrap gap-2">
          <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'contracts.rsvpAccept' | t }}</app-button>
          <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.rsvpDecline' | t }}</app-button>
        </div>
        <p class="mt-1 text-[11px] text-muted">{{ 'contracts.invitationHint' | t }}</p>
      }
      @if (c().kind === 'GOODS_OFFER' || c().kind === 'GOODS_REQUEST') {
        <div class="mt-1 text-[12px] text-text" data-testid="goods-case">{{ (c().kind === 'GOODS_OFFER' ? 'trade.offerText' : 'trade.requestText') | t: { name: c().character?.name ?? '–', quantity: c().quantity, fillType: (c().reference | label: 'fillType'), amount: (c().offerAmount | money), unit: (c().costAmount | money) } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ (c().kind === 'GOODS_OFFER' ? 'trade.buy' : 'trade.sell') | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        } @else {
          <app-badge variant="positive">{{ 'trade.inTransfer' | t }}</app-badge>
        }
        <p class="mt-1 text-[11px] text-muted">{{ (c().kind === 'GOODS_OFFER' ? 'trade.offerHint' : 'trade.requestHint') | t }}</p>
      }
      @if (c().kind === 'NEIGHBOR_MISSION') {
        <div class="mt-1 text-[12px] text-text" data-testid="neighbor-mission">{{ 'trade.missionText' | t: { name: c().character?.name ?? '–', type: (c().reference | label: 'missionType'), hectares: c().hectares ?? '–', bonus: (c().offerAmount | money) } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'trade.acceptMission' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        } @else {
          <app-badge variant="positive">{{ 'trade.missionRunning' | t }}</app-badge>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'trade.missionHint' | t }}</p>
      }
      @if (c().kind === 'FARM_SHOP_ORDER') {
        <div class="mt-1 text-[12px] text-text" data-testid="farm-shop-order">{{ 'trade.shopText' | t: { name: c().character?.name ?? '–', quantity: c().quantity, fillType: (c().reference | label: 'fillType'), amount: (c().offerAmount | money), unit: (c().costAmount | money) } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'trade.deliver' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        } @else if (c().status === 'IN_PROGRESS') {
          <app-badge variant="positive">{{ 'trade.inTransfer' | t }}</app-badge>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'trade.shopHint' | t }}</p>
      }
      @if (c().kind === 'APPRENTICE_TAKEOVER') {
        <div class="mt-1 text-[12px] text-text" data-testid="apprentice-takeover">{{ 'employees.takeoverText' | t: { name: c().character?.name ?? '–', amount: (c().offerAmount | money), skill: c().quantity } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap items-end gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'employees.takeOver' | t }}</app-button>
            <label class="space-y-1">
              <span class="fp-label">{{ 'employees.counterSalary' | t }}</span>
              <input class="fp-input w-32 font-mono" type="number" min="1" step="10" [value]="demand() ?? ''"
                (input)="setDemand($any($event.target).valueAsNumber)" data-testid="demand-input" />
            </label>
            <app-button variant="secondary" [disabled]="busy() || !demand()" (pressed)="act('counter', { amount: demand() })" data-testid="case-counter">{{ 'employees.counterOffer' | t }}</app-button>
            <app-button variant="danger" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'employees.noTakeover' | t }}</app-button>
          </div>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'employees.takeoverHint' | t }}</p>
      }
      @if (c().kind === 'DROUGHT_AID') {
        <div class="mt-1 text-[12px] text-text" data-testid="drought-aid">{{ 'authorities.aidText' | t: { hectares: c().hectares, amount: (c().offerAmount | money) } }}</div>
        @if ((c().costAmount ?? 0) > 0) {
          <div class="mt-1 text-[11px] text-muted" data-testid="aid-deduction">{{ 'authorities.aidDeduction' | t: { amount: (c().costAmount | money) } }}</div>
        }
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'authorities.apply' | t }}</app-button>
          </div>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'authorities.aidHint' | t }}</p>
      }
      @if (c().kind === 'COLLATERAL_CLAIM') {
        <div class="mt-1 text-[12px] text-text" data-testid="collateral-claim">{{ 'credit.claimText' | t: { amount: (c().offerAmount | money), field: c().farmlandId, purpose: c().title ?? '–' } }}</div>
        @if (c().resolution === 'OVERDUE') {
          <app-badge variant="negative" data-testid="claim-overdue">{{ 'credit.claimOverdue' | t }}</app-badge>
        }
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'credit.payClaim' | t }}</app-button>
          </div>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'credit.claimHint' | t }}</p>
      }
      @if (c().kind === 'ANNUAL_REVIEW') {
        <div class="mt-1 text-[12px] text-text" data-testid="annual-review">{{ 'credit.reviewText' | t: { year: c().quantity } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'credit.attend' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        }
      }
      @if (c().kind === 'ANNUAL_REVIEW_OFFER') {
        <div class="mt-1 text-[12px] text-text" data-testid="rate-cut-offer">{{ 'credit.rateCutText' | t: { cut: rateCut() } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'credit.acceptCut' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'credit.rateCutHint' | t }}</p>
      }
      @if (c().kind === 'CONTRACTOR_WORK') {
        <!-- Roadmap V3.1 R31-A1: the contractor comes on the work day -->
        <div class="mt-1 text-[12px] text-text" data-testid="contractor-case">{{ 'farmland.contractor.caseText' | t: { work: (c().reference | label: 'fieldWork'), price: (c().offerAmount | money) } }}
          @if (c().reference === 'SOW' && c().title) { · {{ c().title | label: 'fillType' }} }
          @if (c().reference === 'HARVEST' && c().quantity) { · {{ 'farmland.contractor.liters' | t: { liters: c().quantity, fillType: (c().title | label: 'fillType') } }} }
        </div>
        <p class="mt-1 text-[11px] text-muted">{{ 'farmland.contractor.caseHint' | t }}</p>
      }
      @if (c().kind === 'MACHINE_DEMO_OFFER') {
        <!-- Roadmap V3.1 R31-A2: the workshop offers a demo machine -->
        <div class="mt-1 text-[12px] text-text" data-testid="demo-offer">{{ 'workshop.loans.demoOfferText' | t: { name: c().character?.name ?? '–', vehicle: c().title ?? '–', price: (c().offerAmount | money) } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ 'workshop.loans.demoAccept' | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        }
      }
      @if (c().kind === 'ANIMAL_OFFER' || c().kind === 'ANIMAL_REQUEST') {
        <!-- Roadmap V3.1 R31-A3: livestock trade with a neighbour -->
        <div class="mt-1 text-[12px] text-text" data-testid="animal-case">{{ (c().kind === 'ANIMAL_OFFER' ? 'trade.animals.offerText' : 'trade.animals.requestText') | t: { name: c().character?.name ?? '–', count: c().quantity, subType: c().reference ?? '–', type: (c().title | label: 'animalType'), amount: (c().offerAmount | money), unit: (c().costAmount | money) } }}</div>
        @if (c().status === 'AWAITING_PLAYER') {
          <div class="mt-2 flex flex-wrap gap-2">
            <app-button [disabled]="busy()" (pressed)="act('accept')" data-testid="case-accept">{{ (c().kind === 'ANIMAL_OFFER' ? 'trade.buy' : 'trade.sell') | t }}</app-button>
            <app-button variant="secondary" [disabled]="busy()" (pressed)="act('decline')" data-testid="case-decline">{{ 'contracts.decline' | t }}</app-button>
          </div>
        } @else {
          <app-badge variant="positive">{{ 'trade.inTransfer' | t }}</app-badge>
        }
        <p class="mt-1 text-[11px] text-muted">{{ 'trade.animals.caseHint' | t }}</p>
      }
      @if (isInsuranceCase()) {
        <div class="mt-2 flex flex-wrap gap-2">
          <app-button [disabled]="busy()" (pressed)="act('report', { channel: 'MAIL' })" data-testid="report-mail">{{ 'contracts.reportMail' | t }}</app-button>
          <app-button variant="secondary" [disabled]="busy()" (pressed)="act('report', { channel: 'CALL' })" data-testid="report-call">{{ 'contracts.reportCall' | t }}</app-button>
        </div>
      }
      @if (error(); as e) {
        <p class="mt-2 text-[12px] text-danger" data-testid="case-error">{{ e }}</p>
      }
    </div>
  `,
})
export class CaseCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly c = input.required<CaseView>();
  readonly highlight = input(false);
  /** false inside a task card, which draws the frame itself. */
  readonly framed = input(true);
  readonly changed = output<CaseView>();

  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  /** Counter demand (form field; numbers only via inputs). */
  readonly demand = signal<number | null>(null);

  act(action: string, body: unknown = {}): void {
    this.run(this.api.caseAction(this.c().id, action, body));
  }

  setDemand(value: number): void {
    this.demand.set(Number.isFinite(value) ? value : null);
  }

  /** R3-K3: the offered rate cut in percentage points (reference holds the rate, e.g. 0.0025). */
  rateCut(): string {
    const r = Number(this.c().reference);
    return Number.isFinite(r) ? (Math.round(r * 10000) / 100).toLocaleString('de-DE') : '–';
  }

  isInsuranceCase(): boolean {
    return this.c().kind === 'STORM_DAMAGE' || this.c().kind === 'HAIL_DAMAGE';
  }

  private run(o: Observable<CaseView>): void {
    this.busy.set(true);
    this.error.set(null);
    o.subscribe({
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
}
