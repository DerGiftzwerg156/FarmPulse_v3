import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { SavegameView } from '../api/models';

/**
 * Global live state (Angular Signals): active savegame header data and notification counters.
 * Fed by REST refreshes and the SSE stream (see LiveEventsService).
 */
@Injectable({ providedIn: 'root' })
export class GameStateStore {
  private readonly api = inject(ApiService);

  readonly savegame = signal<SavegameView | null>(null);
  readonly loaded = signal(false);
  readonly unreadMails = computed(() => this.savegame()?.unreadMails ?? 0);
  readonly pendingCalls = computed(() => this.savegame()?.pendingCalls ?? 0);
  readonly hasNotifications = computed(() => this.unreadMails() + this.pendingCalls() > 0);
  /** Incremented on every live event so feature pages can reload reactively. */
  readonly mailVersion = signal(0);
  readonly callVersion = signal(0);
  readonly diaryVersion = signal(0);
  readonly stateVersion = signal(0);
  readonly noticeVersion = signal(0);
  readonly connected = signal(false);

  refresh(): void {
    this.api.savegame().subscribe({
      next: (s) => {
        this.savegame.set(s ?? null);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }
}
