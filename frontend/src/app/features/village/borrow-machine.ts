import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { LoanChoiceView, LoanChoicesView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { Button } from '../../shared/ui/button';

/** Range of the loan days as a list for the select. */
export function loanDays(v: LoanChoicesView): number[] {
  const days: number[] = [];
  for (let d = v.daysMin; d <= v.daysMax; d++) days.push(d);
  return days;
}

/**
 * Roadmap V3.1 R31-A2: "Maschine leihen" on the contact page of a neighbour. The machines come from the shop catalogue
 * by the categories that fit his farm; the rent per game day (trust lowers it) for all days is booked day by day and
 * must be available up front. The running loans are listed in the app "Werkstatt".
 */
@Component({
  selector: 'app-borrow-machine',
  imports: [RouterLink, TranslatePipe, MoneyPipe, Button],
  template: `
    <div class="mt-4 border-t border-border pt-3" data-testid="borrow-machine">
      <div class="fp-label mb-2">{{ 'village.loan.title' | t }}</div>
      @if (choices(); as v) {
        @if (v.choices.length) {
          <form class="flex flex-wrap items-end gap-2" (submit)="$event.preventDefault(); borrow(v)" data-testid="borrow-form">
            <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'village.loan.machine' | t }}
              <select class="fp-input w-64" [value]="selected(v).storeXmlFilename" (change)="xml.set($any($event.target).value)" data-testid="borrow-choice">
                @for (c of v.choices; track c.storeXmlFilename) {
                  <option [value]="c.storeXmlFilename">{{ c.name }} · {{ 'village.loan.perDay' | t: { rent: (c.dailyRent | money) } }}</option>
                }
              </select>
            </label>
            <label class="flex flex-col gap-1 text-[12px] text-muted">{{ 'village.loan.days' | t }}
              <select class="fp-input w-24" [value]="days() ?? v.daysMin" (change)="days.set(+$any($event.target).value)" data-testid="borrow-days">
                @for (d of range(v); track d) { <option [value]="d">{{ d }}</option> }
              </select>
            </label>
            <app-button type="submit" variant="secondary" [disabled]="busy()" data-testid="borrow-submit">{{ 'village.loan.borrow' | t }}</app-button>
            <p class="w-full text-[11px] text-muted" data-testid="borrow-total">
              {{ 'village.loan.total' | t: { total: (selected(v).dailyRent * (days() ?? v.daysMin) | money) } }}
            </p>
          </form>
        } @else {
          <p class="text-[12px] text-muted" data-testid="no-loan">{{ 'village.loan.none' | t }}</p>
        }
      } @else {
        <app-button variant="secondary" [disabled]="busy()" (pressed)="open()" data-testid="borrow-open">{{ 'village.loan.open' | t }}</app-button>
      }
      @if (info(); as i) {
        <p class="mt-2 text-[12px] text-accent" data-testid="borrow-info">{{ i }} <a routerLink="/werkstatt" class="fp-label hover:text-accent">{{ 'village.loan.toWorkshop' | t }}</a></p>
      }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="borrow-error">{{ e }}</p> }
    </div>
  `,
})
export class BorrowMachine {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly neighborId = input.required<number>();

  readonly choices = signal<LoanChoicesView | null>(null);
  readonly xml = signal<string | null>(null);
  readonly days = signal<number | null>(null);
  readonly busy = signal(false);
  readonly info = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.neighborId(); // another contact: start over
      untracked(() => {
        this.choices.set(null);
        this.xml.set(null);
        this.days.set(null);
        this.info.set(null);
        this.error.set(null);
      });
    });
  }

  open(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.loanChoices(this.neighborId()).subscribe({
      next: (v) => {
        this.busy.set(false);
        this.choices.set(v);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  range(v: LoanChoicesView): number[] {
    return loanDays(v);
  }

  selected(v: LoanChoicesView): LoanChoiceView {
    return v.choices.find((c) => c.storeXmlFilename === this.xml()) ?? v.choices[0];
  }

  borrow(v: LoanChoicesView): void {
    const c = this.selected(v);
    const days = this.days() ?? v.daysMin;
    this.busy.set(true);
    this.info.set(null);
    this.error.set(null);
    this.api.borrowMachine(this.neighborId(), c.storeXmlFilename, days).subscribe({
      next: (l) => {
        this.busy.set(false);
        this.choices.set(null);
        this.info.set(this.i18n.t('village.loan.borrowed', { vehicle: l.vehicleName, days: l.days }));
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
