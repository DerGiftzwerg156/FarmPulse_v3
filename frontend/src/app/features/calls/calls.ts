import { Component, OnDestroy, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { MessageView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { Icon } from '../../shared/ui/icon';
import { ListItem } from '../../shared/ui/list-item';
import { PageErrorView } from '../../shared/ui/page-error';

/** Length of the purely cosmetic "soft time window" after accepting (real seconds). Nothing happens when it ends. */
export const SOFT_WINDOW_SECONDS = 90;

export const CALL_BADGE: Record<string, BadgeVariant> = {
  RINGING: 'positive',
  ACCEPTED: 'positive',
  DECLINED: 'negative',
  MISSED: 'warning',
  COMPLETED: 'neutral',
};

/**
 * Calls (AP-8.3): call log with status (RINGING/ACCEPTED/DECLINED/MISSED/COMPLETED) and the conversation view of
 * an accepted call. Replies of the character arrive asynchronously through the same narration pipeline as mails.
 */
@Component({
  selector: 'app-calls',
  imports: [FormsModule, RouterLink, TranslatePipe, LabelPipe, GameTimePipe, Card, ListItem, Badge, Button, Icon, PageErrorView],
  templateUrl: './calls.html',
})
export class Calls implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly id = input<string>();

  readonly all = signal<MessageView[] | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly selectedRoot = signal<number | null>(null);
  readonly text = signal('');
  readonly busy = signal(false);
  readonly actionError = signal<string | null>(null);
  readonly windowLeft = signal(SOFT_WINDOW_SECONDS);
  readonly windowSeconds = SOFT_WINDOW_SECONDS;
  readonly badge = CALL_BADGE;
  private timer: ReturnType<typeof setInterval> | null = null;

  /** Root call entries (one per call), newest first. */
  readonly calls = computed(() => (this.all() ?? []).filter((m) => (m.threadRootId ?? m.id) === m.id));
  readonly selected = computed(() => this.calls().find((c) => c.id === this.selectedRoot()) ?? null);
  readonly conversation = computed(() => {
    const root = this.selectedRoot();
    return (this.all() ?? [])
      .filter((m) => (m.threadRootId ?? m.id) === root)
      .sort((a, b) => a.gameTime - b.gameTime || a.id - b.id);
  });

  constructor() {
    effect(() => {
      this.store.callVersion();
      untracked(() => this.load());
    });
    effect(() => {
      const id = Number(this.id());
      if (id) untracked(() => this.select(id));
    });
  }

  ngOnDestroy(): void {
    this.stopTimer();
  }

  load(): void {
    this.api.callLog().subscribe({
      next: (list) => {
        this.all.set(list);
        this.error.set(null);
        if (this.selectedRoot() === null) {
          const active = this.calls().find((c) => c.callStatus === 'ACCEPTED');
          if (active) this.select(active.id);
        }
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  select(id: number): void {
    if (this.selectedRoot() !== id) {
      this.selectedRoot.set(id);
      this.text.set('');
      this.actionError.set(null);
      this.restartWindow();
    }
  }

  restartWindow(): void {
    this.stopTimer();
    this.windowLeft.set(SOFT_WINDOW_SECONDS);
    this.timer = setInterval(() => {
      this.windowLeft.update((s) => Math.max(0, s - 1));
      if (this.windowLeft() === 0) this.stopTimer();
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  accept(call: MessageView): void {
    this.act(this.api.acceptCall(call.id), () => this.restartWindow());
  }

  decline(call: MessageView): void {
    this.act(this.api.declineCall(call.id));
  }

  hangUp(call: MessageView): void {
    this.act(this.api.completeCall(call.id), () => this.stopTimer());
  }

  say(call: MessageView): void {
    const text = this.text().trim();
    if (!text) return;
    this.act(this.api.sayInCall(call.id, text), () => {
      this.text.set('');
      this.restartWindow();
    });
  }

  private act(obs: ReturnType<ApiService['acceptCall']>, then?: () => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    obs.subscribe({
      next: (m) => {
        this.busy.set(false);
        this.all.update((list) => {
          const rest = (list ?? []).filter((x) => x.id !== m.id);
          return [m, ...rest];
        });
        this.store.refresh();
        then?.();
      },
      error: (e) => {
        this.busy.set(false);
        this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }

  speaker(m: MessageView): string {
    return m.initiatedBy === 'PLAYER' ? this.i18n.t('mailbox.me') : (m.character?.name ?? this.i18n.t('mailbox.system'));
  }
}
