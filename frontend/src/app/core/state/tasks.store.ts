import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../api/api.service';
import { TaskView } from '../api/models';
import { GameStateStore } from './game-state.store';

/**
 * Open decisions of every area (Hof-Tablet "Aufgaben"). Reloaded on every live event, so the badges on the app
 * symbols and the start screen widget stay current without polling.
 */
@Injectable({ providedIn: 'root' })
export class TasksStore {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);

  readonly items = signal<TaskView[]>([]);
  readonly waitingPrompts = signal(0);
  /** Open notices of the hof system (not executed bookings, rewind decision) - shown in "Aufgaben". */
  readonly notices = signal(0);
  readonly loaded = signal(false);
  readonly count = computed(() => this.items().length + this.notices());

  constructor() {
    effect(() => {
      this.store.mailVersion();
      this.store.callVersion();
      this.store.stateVersion();
      this.store.noticeVersion();
      const sg = this.store.savegame();
      untracked(() => (sg ? this.reload() : this.clear()));
    });
  }

  reload(): void {
    this.api.tasks().subscribe({
      next: (t) => {
        this.items.set(t.items);
        this.waitingPrompts.set(t.waitingPrompts);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
    this.api.notices().subscribe({ next: (n) => this.notices.set(n.length), error: () => this.notices.set(0) });
  }

  private clear(): void {
    this.items.set([]);
    this.waitingPrompts.set(0);
    this.notices.set(0);
  }
}
