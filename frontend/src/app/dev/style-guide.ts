import { Component, inject } from '@angular/core';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { Badge } from '../shared/ui/badge';
import { Button } from '../shared/ui/button';
import { Card } from '../shared/ui/card';
import { ChartSeries, ChartWrapper } from '../shared/ui/chart-wrapper';
import { ListItem } from '../shared/ui/list-item';
import { Modal } from '../shared/ui/modal';
import { Stat } from '../shared/ui/stat';
import { ToastService } from '../shared/ui/toast.service';

/** Developer-only page (dev build): visual acceptance of the design tokens and every shared component. */
@Component({
  selector: 'app-style-guide',
  imports: [TranslatePipe, Badge, Button, Card, ChartWrapper, ListItem, Modal, Stat],
  template: `
    <section class="space-y-6">
      <h1 class="font-display text-2xl font-bold uppercase tracking-[0.12em] text-text">{{ 'styleGuide.title' | t }}</h1>
      <app-card [title]="'styleGuide.colors' | t">
        <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
          @for (c of colors; track c.name) {
            <div class="rounded-md border border-border bg-bg p-3">
              <div class="h-12 rounded-sm border border-border" [class]="c.cls"></div>
              <div class="mt-2 font-display text-[11px] uppercase tracking-[0.12em]">{{ c.name }}</div>
              <div class="font-mono text-[10px] text-muted">{{ c.hex }}</div>
            </div>
          }
        </div>
      </app-card>
      <app-card [title]="'styleGuide.typography' | t">
        <p class="font-display text-lg font-bold uppercase tracking-[0.12em]">{{ 'styleGuide.display' | t }}</p>
        <p class="font-body text-sm">{{ 'styleGuide.body' | t }}</p>
        <p class="font-mono text-sm text-accent">{{ 'styleGuide.mono' | t }}</p>
      </app-card>
      <app-card [title]="'styleGuide.components' | t">
        <div class="space-y-4">
          <div class="flex flex-wrap gap-2">
            <app-badge>neutral</app-badge>
            <app-badge variant="positive" [dot]="true" [pulse]="true">positiv</app-badge>
            <app-badge variant="warning">Warnung</app-badge>
            <app-badge variant="negative">negativ</app-badge>
          </div>
          <div class="flex flex-wrap gap-2">
            <app-button (pressed)="toast.show('Primäraktion', 'success')">Primär</app-button>
            <app-button variant="secondary" (pressed)="modalOpen = true">Dialog öffnen</app-button>
            <app-button variant="danger">Gefährlich</app-button>
            <app-button [disabled]="true">Deaktiviert</app-button>
          </div>
          <div class="grid gap-3 sm:grid-cols-3">
            <app-stat label="Kontostand" value="245.000 €" hint="+3,2 % zur Vorwoche" tone="positive" />
            <app-stat label="Offene Kredite" value="2" hint="1 Rate überfällig" tone="warning" />
            <app-stat label="Dorf-Ansehen" value="neutral" />
          </div>
          <div class="space-y-2">
            <app-list-item title="Ihr Kreditantrag wurde genehmigt" subtitle="Frau Berger · Bank" meta="Tag 12" [unread]="true" />
            <app-list-item title="Einladung zum Erntedankfest" subtitle="Genossenschaft" meta="Tag 9" />
          </div>
          <app-chart-wrapper [series]="chart" [xFormat]="day" ariaLabel="Beispiel-Preisverlauf" />
        </div>
      </app-card>
      <app-modal [open]="modalOpen" title="Beispiel-Dialog" (closed)="modalOpen = false">
        <p class="text-sm text-muted">Dialoginhalt</p>
      </app-modal>
    </section>
  `,
})
export class StyleGuide {
  readonly toast = inject(ToastService);
  modalOpen = false;
  readonly day = (x: number) => `Tag ${Math.round(x)}`;
  readonly chart: ChartSeries[] = [
    { key: 'north', label: 'Mühle Nord', colorIndex: 0, points: [{ x: 1, y: 205 }, { x: 3, y: 212 }, { x: 5, y: 240 }, { x: 7, y: 238 }, { x: 9, y: 221 }] },
    { key: 'trade', label: 'Landhandel', colorIndex: 1, points: [{ x: 1, y: 198 }, { x: 3, y: 201 }, { x: 5, y: 199 }, { x: 7, y: 204 }, { x: 9, y: 207 }] },
  ];
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
