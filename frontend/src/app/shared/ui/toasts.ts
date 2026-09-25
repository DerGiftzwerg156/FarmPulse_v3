import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';
import { Icon } from './icon';

/** Renders the global toasts (bottom right). */
@Component({
  selector: 'app-toasts',
  imports: [Icon],
  template: `
    <div class="fixed bottom-20 right-4 z-[60] flex w-80 flex-col gap-2 md:bottom-4" aria-live="polite">
      @for (t of toasts.toasts(); track t.id) {
        <div
          class="flex items-start gap-2 rounded-md border bg-surface p-3 text-[12px] text-text shadow-lg"
          [class.border-border]="t.kind === 'info'"
          [class.border-accent/60]="t.kind === 'success'"
          [class.border-danger/60]="t.kind === 'error'"
          [class.border-warn/60]="t.kind === 'warning'"
          data-testid="toast"
        >
          <app-icon [name]="t.kind === 'error' || t.kind === 'warning' ? 'alert' : 'bell'" size="h-4 w-4" />
          <span class="flex-1">{{ t.text }}</span>
          <button type="button" class="text-muted hover:text-text" (click)="toasts.dismiss(t.id)">
            <app-icon name="close" size="h-3.5 w-3.5" />
          </button>
        </div>
      }
    </div>
  `,
})
export class Toasts {
  readonly toasts = inject(ToastService);
}
