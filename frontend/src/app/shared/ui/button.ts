import { Component, input, output } from '@angular/core';

/** Primary button = bg-accent, secondary = border-border (design reference). */
@Component({
  selector: 'app-button',
  template: `<button
    [type]="type()"
    [disabled]="disabled()"
    (click)="pressed.emit($event)"
    class="inline-flex items-center justify-center gap-1.5 rounded-sm px-3 py-1.5 font-display text-[11px] font-semibold uppercase tracking-[0.12em] transition disabled:cursor-not-allowed disabled:opacity-40"
    [class.bg-accent]="variant() === 'primary'"
    [class.text-bg]="variant() === 'primary'"
    [class.hover:brightness-110]="variant() === 'primary'"
    [class.border]="variant() !== 'primary'"
    [class.border-border]="variant() === 'secondary'"
    [class.text-muted]="variant() === 'secondary'"
    [class.hover:text-text]="variant() === 'secondary'"
    [class.border-danger/60]="variant() === 'danger'"
    [class.text-danger]="variant() === 'danger'"
    [attr.data-variant]="variant()"
  >
    <ng-content />
  </button>`,
})
export class Button {
  readonly variant = input<'primary' | 'secondary' | 'danger'>('primary');
  readonly type = input<'button' | 'submit'>('button');
  readonly disabled = input(false);
  readonly pressed = output<MouseEvent>();
}
