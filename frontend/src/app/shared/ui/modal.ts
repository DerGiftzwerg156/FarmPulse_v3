import { Component, input, output } from '@angular/core';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { Icon } from './icon';

/** Modal dialog with backdrop; closes on backdrop click and Escape. */
@Component({
  selector: 'app-modal',
  imports: [Icon, TranslatePipe],
  host: { '(document:keydown.escape)': 'onEscape()' },
  template: `
    @if (open()) {
      <div class="fixed inset-0 z-50 flex items-center justify-center p-4">
        <button
          type="button"
          class="absolute inset-0 cursor-default bg-bg/80"
          [attr.aria-label]="'common.close' | t"
          (click)="closed.emit()"
          data-testid="modal-backdrop"
        ></button>
        <div
          role="dialog"
          aria-modal="true"
          [attr.aria-label]="title()"
          class="fp-notch relative z-10 w-full max-w-lg rounded-md border border-border bg-surface p-5"
        >
          <header class="mb-3 flex items-center justify-between">
            <h2 class="font-display text-sm font-bold uppercase tracking-[0.14em] text-text">{{ title() }}</h2>
            <button type="button" class="text-muted hover:text-text" (click)="closed.emit()" data-testid="modal-close">
              <app-icon name="close" size="h-4 w-4" />
            </button>
          </header>
          <ng-content />
        </div>
      </div>
    }
  `,
})
export class Modal {
  readonly open = input(false);
  readonly title = input('');
  readonly closed = output<void>();

  onEscape(): void {
    if (this.open()) this.closed.emit();
  }
}
