import { Component } from '@angular/core';
import { TranslatePipe } from '../core/i18n/translate.pipe';

/** Developer-only page (dev build): visual acceptance of the design tokens and shared components. */
@Component({
  selector: 'app-style-guide',
  imports: [TranslatePipe],
  template: `
    <section class="space-y-6 p-6">
      <h1 class="font-display text-2xl font-bold uppercase tracking-[0.12em] text-text">
        {{ 'styleGuide.title' | t }}
      </h1>
      <div>
        <h2 class="fp-heading mb-3">{{ 'styleGuide.colors' | t }}</h2>
        <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
          @for (c of colors; track c.name) {
            <div class="fp-notch relative rounded-md border border-border bg-surface p-3">
              <div class="h-12 rounded-sm border border-border" [class]="c.cls"></div>
              <div class="mt-2 font-display text-[11px] uppercase tracking-[0.12em]">{{ c.name }}</div>
              <div class="font-mono text-[10px] text-muted">{{ c.hex }}</div>
            </div>
          }
        </div>
      </div>
      <div>
        <h2 class="fp-heading mb-3">{{ 'styleGuide.typography' | t }}</h2>
        <p class="font-display text-lg font-bold uppercase tracking-[0.12em]">{{ 'styleGuide.display' | t }}</p>
        <p class="font-body text-sm">{{ 'styleGuide.body' | t }}</p>
        <p class="font-mono text-sm text-accent">{{ 'styleGuide.mono' | t }}</p>
      </div>
      <ng-content />
    </section>
  `,
})
export class StyleGuide {
  readonly colors = [
    { name: 'bg', hex: '#0B0F0D', cls: 'bg-bg' },
    { name: 'surface', hex: '#141A17', cls: 'bg-surface' },
    { name: 'border', hex: '#222E28', cls: 'bg-border' },
    { name: 'accent', hex: '#38B000', cls: 'bg-accent' },
    { name: 'warn', hex: '#FF9F1C', cls: 'bg-warn' },
    { name: 'text', hex: '#E2ECE9', cls: 'bg-text' },
    { name: 'muted', hex: '#7C9088', cls: 'bg-muted' },
    { name: 'danger', hex: '#dc2626', cls: 'bg-danger' },
  ];
}
