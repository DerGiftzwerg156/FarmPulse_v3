import { Component, OnInit, inject, signal } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { OverdraftSettingsView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Owner decisions 2026-10-14: overdraft of the savegame. On: every debit of the tool (salaries, installments, bills,
 * own purchases) may push the balance down to -limit, with interest per year on a negative balance at every month
 * start. Off: no debit below zero; an overdue salary can be paid later with the button in the staff app.
 */
@Component({
  selector: 'app-overdraft-settings-card',
  imports: [TranslatePipe, MoneyPipe, Button, Card],
  template: `
    <app-card [title]="'settings.overdraft.title' | t" data-testid="overdraft-settings">
      @if (view(); as o) {
        <form class="space-y-2" (submit)="$event.preventDefault(); save()">
          <label class="flex items-start gap-2 text-[12px] text-text">
            <input type="checkbox" [checked]="enabled()" (change)="enabled.set($any($event.target).checked); saved.set(false)" data-testid="overdraft-enabled" />
            <span>{{ 'settings.overdraft.enabled' | t }}</span>
          </label>
          <p class="text-[11px] text-muted">{{ (enabled() ? 'settings.overdraft.onHint' : 'settings.overdraft.offHint') | t }}</p>
          <div class="flex flex-wrap items-end gap-2">
            <label class="block space-y-1">
              <span class="fp-label">{{ 'settings.overdraft.limit' | t }}</span>
              <input class="fp-input w-36" type="number" min="0" step="1000" [value]="limit()" [disabled]="!enabled()"
                (input)="limit.set(+$any($event.target).value); saved.set(false)" data-testid="overdraft-limit" />
            </label>
            <label class="block space-y-1">
              <span class="fp-label">{{ 'settings.overdraft.interest' | t }}</span>
              <input class="fp-input w-28" type="number" min="0" step="0.5" [value]="interest()" [disabled]="!enabled()"
                (input)="interest.set(+$any($event.target).value); saved.set(false)" data-testid="overdraft-interest" />
            </label>
            <app-button type="submit" data-testid="overdraft-save">{{ 'common.save' | t }}</app-button>
          </div>
          @if (o.balance < 0) {
            <p class="text-[11px] text-warn" data-testid="overdraft-negative">{{ 'settings.overdraft.negative' | t: { balance: (o.balance | money) } }}</p>
          }
          @if (saved()) {
            <p class="text-[11px] text-accent" data-testid="overdraft-saved">{{ 'settings.saved' | t }}</p>
          }
          @if (error(); as err) {
            <p class="text-[11px] text-danger" role="alert" data-testid="overdraft-error">{{ err }}</p>
          }
        </form>
      } @else {
        <p class="text-[12px] text-muted">–</p>
      }
    </app-card>
  `,
})
export class OverdraftSettingsCard implements OnInit {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  readonly view = signal<OverdraftSettingsView | null>(null);
  readonly enabled = signal(false);
  readonly limit = signal(0);
  readonly interest = signal(0);
  readonly saved = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.api.overdraftSettings().subscribe({ next: (o) => this.apply(o), error: () => this.view.set(null) });
  }

  save(): void {
    this.error.set(null);
    this.api
      .saveOverdraftSettings({ enabled: this.enabled(), limit: Math.max(0, Math.round(this.limit())), interestPercent: Math.max(0, this.interest()) })
      .subscribe({
        next: (o) => {
          this.apply(o);
          this.saved.set(true);
        },
        error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
      });
  }

  private apply(o: OverdraftSettingsView): void {
    this.view.set(o);
    this.enabled.set(o.enabled);
    this.limit.set(o.limit);
    this.interest.set(o.interestPercent);
  }
}
