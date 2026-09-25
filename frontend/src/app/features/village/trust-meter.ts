import { Component, computed, input } from '@angular/core';
import { TrustLevel } from '../../core/api/models';
import { LabelPipe } from '../../shared/format/label.pipe';

const LEVEL: Record<TrustLevel, number> = { BAD: 1, STRAINED: 2, NEUTRAL: 3, GOOD: 4, VERY_GOOD: 5 };

/** Abstract trust display: five segments + word, never the underlying number (no formula disclosure). */
@Component({
  selector: 'app-trust-meter',
  imports: [LabelPipe],
  template: `
    <span class="inline-flex items-center gap-1.5" [attr.title]="level() | label: 'trustLevel'" data-testid="trust-meter" [attr.data-level]="level()">
      <span class="inline-flex gap-0.5" aria-hidden="true">
        @for (i of segments; track i) {
          <span class="h-2.5 w-1.5 rounded-[1px]"
            [class.bg-accent]="i <= filled() && filled() >= 4"
            [class.bg-muted]="i <= filled() && filled() === 3"
            [class.bg-warn]="i <= filled() && filled() === 2"
            [class.bg-danger]="i <= filled() && filled() === 1"
            [class.bg-border]="i > filled()"></span>
        }
      </span>
      <span class="fp-label">{{ level() | label: 'trustLevel' }}</span>
    </span>
  `,
})
export class TrustMeter {
  readonly level = input.required<TrustLevel>();
  readonly segments = [1, 2, 3, 4, 5];
  readonly filled = computed(() => LEVEL[this.level()] ?? 3);
}
