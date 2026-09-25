import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { MessageView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Button } from '../../shared/ui/button';
import { Icon } from '../../shared/ui/icon';

/**
 * Global incoming-call overlay (SSE-triggered): shows the first RINGING call with accept/decline and a clear hint
 * about the consequences of declining. Accepting opens the conversation on the calls page.
 */
@Component({
  selector: 'app-call-overlay',
  imports: [TranslatePipe, LabelPipe, GameTimePipe, Button, Icon],
  template: `
    @if (ringing(); as call) {
      <div class="fixed inset-x-3 bottom-3 z-50 sm:inset-x-auto sm:right-4 sm:w-96" role="alertdialog" aria-live="assertive"
        [attr.aria-label]="'calls.incoming' | t" data-testid="call-overlay">
        <div class="fp-notch fp-glow relative rounded-md border border-accent/60 bg-surface p-4">
          <div class="flex items-center gap-3">
            <span class="flex h-10 w-10 items-center justify-center rounded-full border border-accent/60 text-accent fp-pulse-dot">
              <app-icon name="phone" />
            </span>
            <div class="min-w-0 flex-1">
              <div class="fp-label text-accent">{{ 'calls.incoming' | t }}</div>
              <div class="truncate font-display text-[14px] font-bold text-text" data-testid="caller">{{ call.character?.name ?? ('mailbox.system' | t) }}</div>
              @if (call.character) {
                <div class="fp-label">{{ call.character.role | label: 'characterRole' }}</div>
              }
            </div>
          </div>
          @if (call.subject) {
            <p class="mt-3 font-body text-[13px] text-text">{{ call.subject }}</p>
          }
          @if (call.ringDeadlineGameTime) {
            <p class="mt-1 font-mono text-[10px] text-muted">{{ 'calls.ringsUntil' | t: { at: (call.ringDeadlineGameTime | gameTime) } }}</p>
          }
          <p class="mt-3 rounded-sm border border-warn/40 px-2 py-1.5 text-[11px] text-warn" data-testid="decline-hint">{{ 'calls.declineHint' | t }}</p>
          @if (error()) {
            <p class="mt-2 text-[12px] text-danger">{{ error() }}</p>
          }
          <div class="mt-3 flex justify-end gap-2">
            <app-button variant="danger" [disabled]="busy()" (pressed)="decline(call)" data-testid="call-decline">
              <app-icon name="phoneOff" size="h-3.5 w-3.5" /> {{ 'calls.decline' | t }}
            </app-button>
            <app-button [disabled]="busy()" (pressed)="accept(call)" data-testid="call-accept">
              <app-icon name="phone" size="h-3.5 w-3.5" /> {{ 'calls.accept' | t }}
            </app-button>
          </div>
        </div>
      </div>
    }
  `,
})
export class CallOverlay {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly router = inject(Router);
  private readonly i18n = inject(TranslationService);

  readonly pending = signal<MessageView[]>([]);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly ringing = computed(() => this.pending().find((c) => c.callStatus === 'RINGING') ?? null);

  constructor() {
    effect(() => {
      this.store.callVersion();
      this.store.stateVersion();
      if (this.store.savegame()) {
        untracked(() => this.load());
      }
    });
  }

  load(): void {
    this.api.pendingCalls().subscribe({ next: (p) => this.pending.set(p), error: () => this.pending.set([]) });
  }

  accept(call: MessageView): void {
    this.act(this.api.acceptCall(call.id), call, () => void this.router.navigate(['/calls'], { queryParams: { id: call.id } }));
  }

  decline(call: MessageView): void {
    this.act(this.api.declineCall(call.id), call);
  }

  private act(obs: ReturnType<ApiService['acceptCall']>, call: MessageView, then?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    obs.subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.pending.update((p) => p.map((c) => (c.id === call.id ? updated : c)));
        this.store.callVersion.update((v) => v + 1);
        this.store.refresh();
        then?.();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
        this.load();
      },
    });
  }
}
