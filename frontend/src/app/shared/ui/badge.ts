import { Component, input } from '@angular/core';

export type BadgeVariant = 'neutral' | 'positive' | 'negative' | 'warning';

/** Status badge (neutral / positive / negative / warning), colors from the design tokens. */
@Component({
  selector: 'app-badge',
  template: `<span
    class="inline-flex items-center gap-1.5 rounded-sm border bg-bg px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.14em]"
    [class.border-border]="variant() === 'neutral'"
    [class.text-muted]="variant() === 'neutral'"
    [class.border-accent/40]="variant() === 'positive'"
    [class.text-accent]="variant() === 'positive'"
    [class.border-danger/50]="variant() === 'negative'"
    [class.text-danger]="variant() === 'negative'"
    [class.border-warn/50]="variant() === 'warning'"
    [class.text-warn]="variant() === 'warning'"
    [attr.data-variant]="variant()"
  >
    @if (dot()) {
      <span class="h-1.5 w-1.5 rounded-full bg-current" [class.fp-pulse-dot]="pulse()"></span>
    }
    <ng-content />
  </span>`,
})
export class Badge {
  readonly variant = input<BadgeVariant>('neutral');
  readonly dot = input(false);
  readonly pulse = input(false);
}
