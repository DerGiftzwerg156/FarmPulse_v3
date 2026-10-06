import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';

/**
 * First-open hints of the apps (owner decision 2026-10-06): the backend remembers which hints were confirmed with
 * "Verstanden", so a hint shows once per installation on any device. Until the list is loaded no hint opens.
 */
@Injectable({ providedIn: 'root' })
export class AppHintsStore {
  private readonly api = inject(ApiService);

  readonly seen = signal<ReadonlySet<string>>(new Set());
  readonly loaded = signal(false);

  load(): void {
    this.api.appHints().subscribe({
      next: (h) => {
        this.seen.set(new Set(h.seen));
        this.loaded.set(true);
      },
      // without the list no hint is forced on the player
      error: () => this.loaded.set(false),
    });
  }

  isSeen(appId: string): boolean {
    return this.seen().has(appId);
  }

  /** Marks the hint as read at once; the backend keeps it for every other device. */
  markSeen(appId: string): void {
    this.seen.update((s) => new Set([...s, appId]));
    // merge, never drop: a hint confirmed here stays closed even if the answer is late or incomplete
    this.api.markAppHintSeen(appId).subscribe({ next: (h) => this.seen.update((s) => new Set([...s, ...(h?.seen ?? [])])), error: () => undefined });
  }
}
