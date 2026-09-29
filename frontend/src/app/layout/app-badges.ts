import { Injectable, computed, inject } from '@angular/core';
import { GameStateStore } from '../core/state/game-state.store';

/** Badge counters on the app symbols (start screen, dock, quick bar). */
@Injectable({ providedIn: 'root' })
export class AppBadges {
  private readonly store = inject(GameStateStore);

  /** App id -> number shown on the symbol (0 = no badge). */
  readonly counts = computed<Record<string, number>>(() => ({
    mail: this.store.unreadMails(),
    phone: this.store.pendingCalls(),
  }));

  count(id: string): number {
    return this.counts()[id] ?? 0;
  }
}
