import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

/** KPI tile: small uppercase label + large mono value (like the design reference's key-figure cards). */
@Component({
  selector: 'app-stat',
  imports: [RouterLink],
  template: `
    <div class="fp-notch group relative rounded-md border border-border bg-surface p-4">
      <div class="flex items-start justify-between">
        <div class="fp-label">{{ label() }}</div>
        @if (link()) {
          <a [routerLink]="link()" class="fp-label hover:text-accent">→</a>
        }
      </div>
      <div class="mt-3 font-mono font-bold text-text" [class.text-2xl]="!compact()" [class.text-lg]="compact()" data-testid="stat-value">{{ value() }}</div>
      @if (hint()) {
        <div
          class="mt-0.5 font-body text-[11px]"
          [class.text-muted]="tone() === 'neutral'"
          [class.text-accent]="tone() === 'positive'"
          [class.text-warn]="tone() === 'warning'"
          [class.text-danger]="tone() === 'negative'"
        >
          {{ hint() }}
        </div>
      }
    </div>
  `,
})
export class Stat {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly hint = input<string>();
  readonly tone = input<'neutral' | 'positive' | 'warning' | 'negative'>('neutral');
  readonly link = input<string>();
  /** Smaller value font for word values (e.g. a reputation tier). */
  readonly compact = input(false);
}
