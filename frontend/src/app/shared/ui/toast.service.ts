import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  text: string;
  kind: 'info' | 'success' | 'error' | 'warning';
}

/** Global notifications (e.g. "Neue Mail von …", API errors). */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private next = 1;
  readonly toasts = signal<Toast[]>([]);

  show(text: string, kind: Toast['kind'] = 'info', durationMs = 5000): number {
    const id = this.next++;
    this.toasts.update((t) => [...t, { id, text, kind }]);
    if (durationMs > 0) {
      setTimeout(() => this.dismiss(id), durationMs);
    }
    return id;
  }

  dismiss(id: number): void {
    this.toasts.update((t) => t.filter((x) => x.id !== id));
  }
}
