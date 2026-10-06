import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { apiErrorMessage } from '../../core/api/api-error';
import { DirectPaymentStatusView, DirectPaymentView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V3.1 R31-B1: "Sammelantrag" in the app "Ämter". The open application shows the own fields with their
 * current crop; the player confirms or corrects the crop per field and submits (late = cut per day). Submitted and
 * past applications show the on-site check and the paid premium.
 */
@Component({
  selector: 'app-direct-payment-card',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Badge, Button, Card],
  template: `
    <app-card [title]="'authorities.directPayment.title' | t" [highlight]="highlighted()" data-testid="direct-payment">
      @if (status(); as s) {
        <p class="mb-3 text-[12px] text-muted">{{ 'authorities.directPayment.intro' | t: { premium: (s.premiumPerHa | money), late: s.lateCutPercentPerDay, days: s.lateMaxDays } }}</p>
        @for (a of s.applications; track a.id) {
          <div class="mb-3 rounded-xl border border-border p-3" data-testid="direct-payment-application" [attr.data-status]="a.status">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <span class="font-display text-[12px] font-bold text-text">{{ 'authorities.directPayment.year' | t: { year: a.cropYear } }}</span>
              <app-badge [variant]="a.status === 'LAPSED' ? 'negative' : a.status === 'OPEN' ? 'warning' : 'positive'">{{ a.status | label: 'directPaymentStatus' }}</app-badge>
            </div>
            @if (a.status === 'OPEN') {
              <p class="mt-1 text-[12px] text-text">{{ 'authorities.directPayment.deadline' | t: { time: (a.deadlineGameTime | gameTime), late: (a.lateLimitGameTime | gameTime) } }}</p>
              @if (s.form.length) {
                <ul class="mt-2 space-y-1.5" data-testid="direct-payment-form">
                  @for (f of s.form; track f.farmlandId) {
                    <li class="flex flex-wrap items-center gap-2 text-[12px]" data-testid="direct-payment-field" [attr.data-field]="f.farmlandId">
                      <label class="flex items-center gap-1">
                        <input type="checkbox" [checked]="included(f.farmlandId)" (change)="toggle(f.farmlandId, $any($event.target).checked)" data-testid="direct-payment-include" />
                        <span class="w-28 text-text">{{ 'contracts.field' | t: { id: f.fieldName } }}</span>
                      </label>
                      <span class="w-16 font-mono text-muted">{{ f.hectares }} ha</span>
                      <select class="fp-input w-40" [value]="crop(f.farmlandId, f.suggestedCrop)" (change)="setCrop(f.farmlandId, $any($event.target).value)" data-testid="direct-payment-crop">
                        @for (c of s.crops; track c) {
                          <option [value]="c" [selected]="c === crop(f.farmlandId, f.suggestedCrop)">{{ c | label: 'fillType' }}</option>
                        }
                      </select>
                    </li>
                  }
                </ul>
                <div class="mt-2">
                  <app-button [disabled]="busy() || !selectedCount()" (pressed)="submit(a)" data-testid="direct-payment-submit">{{ 'authorities.directPayment.submit' | t: { count: selectedCount() } }}</app-button>
                </div>
              } @else {
                <p class="mt-1 text-[12px] text-muted">{{ 'authorities.directPayment.noFields' | t }}</p>
              }
            } @else {
              <ul class="mt-2 space-y-1 text-[12px]">
                @for (f of a.fields; track f.farmlandId) {
                  <li class="flex flex-wrap gap-2" data-testid="direct-payment-line">
                    <span class="w-28 text-text">{{ 'contracts.field' | t: { id: f.fieldName } }}</span>
                    <span class="w-16 font-mono text-muted">{{ f.hectares }} ha</span>
                    <span class="text-text">{{ f.declaredCrop | label: 'fillType' }}</span>
                    @if (f.actualCrop && f.actualCrop !== f.declaredCrop) {
                      <span class="text-danger" data-testid="direct-payment-deviation">{{ 'authorities.directPayment.found' | t: { crop: (f.actualCrop | label: 'fillType') } }}</span>
                    }
                    @if (f.rotationRepeat) {
                      <span class="text-warn">{{ 'authorities.directPayment.rotation' | t }}</span>
                    }
                  </li>
                }
              </ul>
              @if (a.lateDays > 0) {
                <p class="mt-1 text-[12px] text-warn">{{ 'authorities.directPayment.lateDays' | t: { days: a.lateDays } }}</p>
              }
              <p class="mt-1 text-[12px] text-muted" data-testid="direct-payment-check">{{ a.checkStatus | label: 'directPaymentCheck' }}
                @if ((a.deviationCut ?? 0) + (a.rotationCut ?? 0) > 0) { · {{ 'authorities.directPayment.cut' | t: { amount: (((a.deviationCut ?? 0) + (a.rotationCut ?? 0)) | money) } }} }</p>
              @if (a.paidAmount !== null) {
                <p class="mt-1 text-[12px] text-text" data-testid="direct-payment-paid">{{ 'authorities.directPayment.paid' | t: { amount: (a.paidAmount | money), premium: (a.premium | money) } }}</p>
              }
            }
          </div>
        } @empty {
          <p class="text-sm text-muted">{{ 'authorities.directPayment.none' | t }}</p>
        }
      } @else {
        <p class="text-[12px] text-muted">{{ 'common.loading' | t }}</p>
      }
      @if (info(); as i) { <p class="mt-2 text-[12px] text-accent" data-testid="direct-payment-info">{{ i }}</p> }
      @if (error(); as e) { <p class="mt-2 text-[12px] text-danger" data-testid="direct-payment-error">{{ e }}</p> }
    </app-card>
  `,
})
export class DirectPaymentCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  private readonly store = inject(GameStateStore);
  private readonly tasks = inject(TasksStore);

  /** `?directPayment=` of a mail link. */
  readonly highlight = input<number | null>(null);

  readonly status = signal<DirectPaymentStatusView | null>(null);
  /** Corrections of the crop per field (form field; absent = the suggestion). */
  private readonly crops = signal<Record<number, string>>({});
  /** Fields left out of the application. */
  private readonly excluded = signal<Set<number>>(new Set());
  readonly busy = signal(false);
  readonly info = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly highlighted = computed(() => {
    const id = this.highlight();
    return id !== null && (this.status()?.applications.some((a) => a.id === id) ?? false);
  });
  readonly selectedCount = computed(() => (this.status()?.form ?? []).filter((f) => !this.excluded().has(f.farmlandId)).length);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.directPayment().subscribe({
      next: (s) => this.status.set(s),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  crop(farmlandId: number, suggested: string): string {
    return this.crops()[farmlandId] ?? suggested;
  }

  setCrop(farmlandId: number, crop: string): void {
    this.crops.update((c) => ({ ...c, [farmlandId]: crop }));
  }

  included(farmlandId: number): boolean {
    return !this.excluded().has(farmlandId);
  }

  toggle(farmlandId: number, on: boolean): void {
    this.excluded.update((s) => {
      const n = new Set(s);
      if (on) n.delete(farmlandId);
      else n.add(farmlandId);
      return n;
    });
  }

  submit(a: DirectPaymentView): void {
    const fields = (this.status()?.form ?? []).filter((f) => this.included(f.farmlandId))
      .map((f) => ({ farmlandId: f.farmlandId, crop: this.crop(f.farmlandId, f.suggestedCrop) }));
    this.busy.set(true);
    this.info.set(null);
    this.error.set(null);
    this.api.submitDirectPayment(a.id, fields).subscribe({
      next: () => {
        this.busy.set(false);
        this.info.set(this.i18n.t('authorities.directPayment.submitted'));
        this.load();
        this.tasks.reload();
        this.store.refresh();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
