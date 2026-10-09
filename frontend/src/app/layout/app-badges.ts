import { Injectable, computed, inject } from '@angular/core';
import { GameStateStore } from '../core/state/game-state.store';
import { TasksStore } from '../core/state/tasks.store';
import { taskAppId, taskTabId } from './task-apps';

/** Badge counters on the app symbols (start screen, dock, quick bar). */
@Injectable({ providedIn: 'root' })
export class AppBadges {
  private readonly store = inject(GameStateStore);
  private readonly tasks = inject(TasksStore);

  /**
   * App id -> number shown on the symbol (0 = no badge): unread mails, pending calls, all open tasks on "Aufgaben"
   * and the open tasks of each other app on its own symbol.
   */
  readonly counts = computed<Record<string, number>>(() => {
    const counts: Record<string, number> = {};
    for (const t of this.tasks.items()) {
      const id = taskAppId(t);
      counts[id] = (counts[id] ?? 0) + 1;
    }
    counts['mail'] = this.store.unreadMails();
    counts['phone'] = this.store.pendingCalls();
    counts['tasks'] = this.tasks.count();
    return counts;
  });

  /**
   * "app/tab" -> open tasks of that tab (owner decision 2026-10-06: the app counters split onto the tabs); in
   * "Aufgaben" the tasks and the notices of the hof system.
   */
  readonly tabCounts = computed<Record<string, number>>(() => {
    const counts: Record<string, number> = {};
    for (const t of this.tasks.items()) {
      const tab = taskTabId(t);
      if (tab) {
        const key = `${taskAppId(t)}/${tab}`;
        counts[key] = (counts[key] ?? 0) + 1;
      }
    }
    counts['tasks/aufgaben'] = this.tasks.items().length;
    counts['tasks/meldungen'] = this.tasks.notices();
    return counts;
  });

  count(id: string): number {
    return this.counts()[id] ?? 0;
  }

  tabCount(appId: string, tabId: string): number {
    return this.tabCounts()[`${appId}/${tabId}`] ?? 0;
  }
}
