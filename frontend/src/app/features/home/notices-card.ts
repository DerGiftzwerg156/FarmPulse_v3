import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { NoticeView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { formatGameTime, formatMoney } from '../../shared/format/format';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/**
 * Notices of the fact layer (TODO T-02 / T-03): bookings the game did not execute and the decision after loading an
 * older savegame. Hidden while there is nothing to show.
 */
@Component({
  selector: 'app-notices-card',
  imports: [TranslatePipe, Card, Button],
  template: `
    @if (notices().length || mods().length) {
      <app-card [title]="'notices.title' | t" [highlight]="true" data-testid="notices">
        <ul class="space-y-2">
          @if (mods().length) {
            <li class="rounded-md border border-warn/40 bg-bg p-3" data-testid="conflict-mods">
              <div class="font-display text-[12px] font-bold text-text">{{ 'notices.conflictTitle' | t }}</div>
              <p class="mt-1 text-[12px] text-muted">{{ 'notices.conflictText' | t: { mods: mods().join(', ') } }}</p>
            </li>
          }
          @for (n of notices(); track n.id) {
            <li class="rounded-md border border-warn/40 bg-bg p-3" data-testid="notice" [attr.data-kind]="n.kind">
              <div class="font-display text-[12px] font-bold text-text">{{ title(n) }}</div>
              <p class="mt-1 text-[12px] text-muted">{{ text(n) }}</p>
              @if (n.kind === 'REWIND_DECISION') {
                <p class="mt-1 text-[11px] text-muted">{{ 'notices.rewindHint' | t }}</p>
                <div class="mt-2 flex flex-wrap gap-2">
                  <app-button [disabled]="busy()" (pressed)="resolve(n, 'RESEND')" data-testid="notice-resend">{{ 'notices.resend' | t }}</app-button>
                  <app-button variant="secondary" [disabled]="busy()" (pressed)="resolve(n, 'KEEP')" data-testid="notice-keep">{{ 'notices.keep' | t }}</app-button>
                </div>
              } @else {
                <div class="mt-2">
                  <app-button variant="secondary" [disabled]="busy()" (pressed)="resolve(n, 'DISMISS')" data-testid="notice-dismiss">{{ 'notices.dismiss' | t }}</app-button>
                </div>
              }
            </li>
          }
        </ul>
      </app-card>
    }
  `,
})
export class NoticesCard {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly notices = signal<NoticeView[]>([]);
  readonly busy = signal(false);
  /** TODO T-09: installed mods with overlapping features (warning only, nothing is disabled). */
  readonly mods = computed(() => this.store.savegame()?.detectedMods ?? []);

  constructor() {
    effect(() => {
      this.store.noticeVersion();
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.notices().subscribe({ next: (n) => this.notices.set(n), error: () => this.notices.set([]) });
  }

  resolve(n: NoticeView, action: string): void {
    this.busy.set(true);
    this.api.resolveNotice(n.id, action).subscribe({
      next: () => {
        this.busy.set(false);
        this.notices.update((all) => all.filter((x) => x.id !== n.id));
      },
      error: () => this.busy.set(false),
    });
  }

  title(n: NoticeView): string {
    return this.i18n.t(`notices.${n.kind}.title`);
  }

  /** Renders the raw notice details into a sentence (numbers come from the backend, never from the AI). */
  text(n: NoticeView): string {
    const d = n.details;
    const num = (k: string) => Number(d[k] ?? 0);
    if (n.kind === 'INSTRUCTION_FAILED') {
      const what = d['type'] === 'MONEY_TRANSACTION'
        ? this.label('moneyReason', String(d['reason'] ?? ''))
        : this.label('instructionType', String(d['type'] ?? ''));
      const reason = d['message'] === 'INSUFFICIENT_FUNDS' ? this.i18n.t('notices.insufficientFunds') : String(d['message'] ?? '');
      const amount = d['amount'] !== undefined ? formatMoney(Math.abs(num('amount'))) : d['price'] !== undefined ? formatMoney(num('price')) : '';
      return this.i18n.t('notices.INSTRUCTION_FAILED.text', { what, amount, reason });
    }
    return this.i18n.t(`notices.${n.kind}.text`, {
      count: num('count'),
      money: formatMoney(num('moneyTotal')),
      from: formatGameTime(num('previousGameTime')),
      to: formatGameTime(num('rewoundToGameTime')),
    });
  }

  private label(group: string, value: string): string {
    const key = `enums.${group}.${value}`;
    return this.i18n.has(key) ? this.i18n.t(key) : value;
  }
}
