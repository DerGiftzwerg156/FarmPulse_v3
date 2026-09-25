import { Component, input } from '@angular/core';

/** Timeline / list row for mails and diary entries. */
@Component({
  selector: 'app-list-item',
  template: `
    <div
      class="fp-notch relative flex gap-3 rounded-md border bg-bg p-3 transition"
      [class.border-border]="!active()"
      [class.border-accent/60]="active()"
      [class.hover:border-accent/40]="interactive()"
      [class.cursor-pointer]="interactive()"
    >
      @if (unread()) {
        <span class="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-accent fp-pulse-dot" data-testid="unread-dot"></span>
      }
      <div class="min-w-0 flex-1">
        <div class="flex items-center justify-between gap-2">
          <div class="truncate font-display text-[12px] text-text" [class.font-bold]="unread()">{{ title() }}</div>
          <div class="shrink-0 font-mono text-[10px] text-muted">{{ meta() }}</div>
        </div>
        @if (subtitle()) {
          <div class="mt-0.5 truncate font-body text-[11px] text-muted">{{ subtitle() }}</div>
        }
        <ng-content />
      </div>
    </div>
  `,
})
export class ListItem {
  readonly title = input.required<string>();
  readonly subtitle = input<string | null>();
  readonly meta = input<string>('');
  readonly unread = input(false);
  readonly active = input(false);
  readonly interactive = input(true);
}
