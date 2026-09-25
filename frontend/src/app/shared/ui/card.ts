import { Component, input } from '@angular/core';

/** Panel in the look of the design reference: surface, border, rounded-md, green notch corners on hover. */
@Component({
  selector: 'app-card',
  host: { class: 'block' },
  template: `
    <section
      class="fp-notch group relative rounded-md border p-4"
      [class.bg-surface]="variant() === 'surface'"
      [class.bg-bg]="variant() === 'inset'"
      [class.border-border]="!highlight()"
      [class.border-accent]="highlight()"
    >
      @if (title()) {
        <header class="mb-3 flex items-center justify-between gap-2">
          <h2 class="fp-heading">{{ title() }}</h2>
          <ng-content select="[card-actions]" />
        </header>
      }
      <ng-content />
    </section>
  `,
})
export class Card {
  readonly title = input<string>();
  readonly variant = input<'surface' | 'inset'>('surface');
  readonly highlight = input(false);
}
